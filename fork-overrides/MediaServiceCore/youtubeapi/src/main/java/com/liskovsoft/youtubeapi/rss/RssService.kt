package com.liskovsoft.youtubeapi.rss

import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem
import com.liskovsoft.sharedutils.helpers.Helpers
import com.liskovsoft.youtubeapi.app.nsigsolver.common.YouTubeInfoExtractor
import com.liskovsoft.youtubeapi.browse.v2.BrowseService2
import com.liskovsoft.youtubeapi.browse.v2.BrowseService2Wrapper
import com.liskovsoft.youtubeapi.service.data.YouTubeMediaGroup
import com.liskovsoft.youtubeapi.service.data.YouTubeMediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

internal object RssService {
    private const val RSS_URL: String = "https://www.youtube.com/feeds/videos.xml?channel_id="
    private const val MAX_ITEMS = 100
    private const val MAX_CONCURRENT_FETCHES = 4

    // A fresh entry is returned without touching YouTube.
    private const val FRESH_TTL_MS = 10 * 60 * 1000L

    // Once stale, return the last good feed immediately and refresh it quietly.
    // Very old data is refreshed synchronously, but still remains the fallback if
    // YouTube is unavailable.
    private const val STALE_TTL_MS = 24 * 60 * 60 * 1000L

    // Avoid hammering YouTube when an anonymous request is being rejected.
    private const val FAILURE_BACKOFF_MS = 5 * 60 * 1000L

    private data class CacheEntry(
        val fetchedAtMs: Long,
        val items: List<MediaItem>
    )

    private val feedCache = ConcurrentHashMap<String, CacheEntry>()
    private val retryAfterMs = ConcurrentHashMap<String, Long>()
    private val channelLocks = ConcurrentHashMap<String, Mutex>()
    private val refreshesInFlight =
        Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    // RSS download + channel enrichment both happen while holding this permit.
    // The old implementation could fan out close to 100 channel requests at once.
    private val networkGate = Semaphore(MAX_CONCURRENT_FETCHES)
    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @JvmStatic
    @JvmOverloads
    fun getFeed(vararg channelIds: String, type: Int = -1): MediaGroup? {
        val items = fetchFeedsSafe(channelIds.take(MAX_ITEMS)) ?: return null

        items.sortByDescending { it.publishedDate }

        return YouTubeMediaGroup(type).apply {
            mediaItems = items
        }
    }

    private fun fetchFeedsSafe(channelIds: List<String>): MutableList<MediaItem>? {
        try {
            return fetchFeeds(channelIds)
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }

        return null
    }

    private fun fetchFeeds(channelIds: List<String>): MutableList<MediaItem> = runBlocking {
        coroutineScope {
            channelIds
                .map { channelId ->
                    async {
                        fetchFeedCached(channelId)
                    }
                }
                .mapNotNull { it.await() }
                .flatten()
                .toMutableList()
        }
    }

    /**
     * Returns fresh cached data immediately. Stale-but-usable data is also
     * returned immediately and refreshed in the background. A channel with no
     * cache performs one foreground fetch.
     */
    private suspend fun fetchFeedCached(channelId: String): List<MediaItem>? {
        val now = System.currentTimeMillis()
        val cached = feedCache[channelId]

        if (cached != null) {
            val age = now - cached.fetchedAtMs

            if (age <= FRESH_TTL_MS) {
                return cached.items
            }

            if (age <= STALE_TTL_MS) {
                scheduleRefresh(channelId)
                return cached.items
            }
        }

        if (isBackedOff(channelId, now)) {
            return cached?.items
        }

        return channelLock(channelId).withLock {
            val lockedNow = System.currentTimeMillis()
            val lockedCached = feedCache[channelId]

            // Another caller may have filled the cache while we waited.
            if (lockedCached != null && lockedNow - lockedCached.fetchedAtMs <= FRESH_TTL_MS) {
                return@withLock lockedCached.items
            }

            if (isBackedOff(channelId, lockedNow)) {
                return@withLock lockedCached?.items ?: cached?.items
            }

            val fresh = fetchFeedNetworkLimited(channelId)

            if (fresh != null) {
                storeFresh(channelId, fresh)
                fresh
            } else {
                markFailure(channelId)
                lockedCached?.items ?: cached?.items
            }
        }
    }

    private fun scheduleRefresh(channelId: String) {
        val now = System.currentTimeMillis()

        if (isBackedOff(channelId, now) || !refreshesInFlight.add(channelId)) {
            return
        }

        refreshScope.launch {
            try {
                channelLock(channelId).withLock {
                    val lockedNow = System.currentTimeMillis()
                    val cached = feedCache[channelId]

                    // A foreground caller or another refresh may already have won.
                    if (cached != null && lockedNow - cached.fetchedAtMs <= FRESH_TTL_MS) {
                        return@withLock
                    }

                    if (isBackedOff(channelId, lockedNow)) {
                        return@withLock
                    }

                    val fresh = fetchFeedNetworkLimited(channelId)

                    if (fresh != null) {
                        storeFresh(channelId, fresh)
                    } else {
                        markFailure(channelId)
                    }
                }
            } finally {
                refreshesInFlight.remove(channelId)
            }
        }
    }

    private suspend fun fetchFeedNetworkLimited(channelId: String): List<MediaItem>? =
        networkGate.withPermit {
            fetchFeedNetwork(channelId)
        }

    private suspend fun fetchFeedNetwork(channelId: String): List<MediaItem>? =
        withContext(Dispatchers.IO) {
            try {
                val rssContent = YouTubeInfoExtractor.downloadWebpage(RSS_URL + channelId)
                val result = YouTubeRssParser(Helpers.toStream(rssContent)).parse()
                syncWithChannel(channelId, result)
                result
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }

    private fun storeFresh(channelId: String, items: List<MediaItem>) {
        feedCache[channelId] = CacheEntry(
            fetchedAtMs = System.currentTimeMillis(),
            items = items.toList()
        )
        retryAfterMs.remove(channelId)
    }

    private fun markFailure(channelId: String) {
        retryAfterMs[channelId] = System.currentTimeMillis() + FAILURE_BACKOFF_MS
    }

    private fun isBackedOff(channelId: String, now: Long): Boolean =
        (retryAfterMs[channelId] ?: 0L) > now

    private fun channelLock(channelId: String): Mutex {
        val current = channelLocks[channelId]

        if (current != null) {
            return current
        }

        val created = Mutex()
        return channelLocks.putIfAbsent(channelId, created) ?: created
    }

    /**
     * Add properties missing from RSS and, when the channel response looks
     * complete enough, use it to filter content such as Shorts.
     *
     * Critically, a failed/partial channel enrichment must never erase an
     * otherwise valid RSS feed. That was a common path to an empty local
     * subscription group when YouTube throttled anonymous requests.
     */
    private fun syncWithChannel(channelId: String, result: List<MediaItem>) {
        if (result.isEmpty()) {
            return
        }

        val group = getBrowseService2().getChannelAsGrid(channelId)
        val originItems = group?.mediaItems

        if (originItems.isNullOrEmpty()) {
            return
        }

        val originByVideoId = HashMap<String, MediaItem>()

        for (originItem in originItems) {
            val videoId = originItem?.videoId
            if (videoId != null) {
                originByVideoId[videoId] = originItem
            }
        }

        if (originByVideoId.isEmpty()) {
            return
        }

        var matchedItems = 0

        for (item in result) {
            val origin = originByVideoId[item.videoId] ?: continue
            matchedItems++

            if (item is YouTubeMediaItem) {
                item.badgeText = origin.badgeText
                item.isLive = origin.isLive
                item.isUpcoming = origin.isUpcoming
                item.videoPreviewUrl = origin.videoPreviewUrl
                item.percentWatched = origin.percentWatched
            }
        }

        // Only remove unmatched RSS entries when the enrichment response has
        // enough overlap to look trustworthy. A throttled/partial response can
        // otherwise remove every item and make the group appear signed out.
        if (matchedItems * 2 >= result.size) {
            Helpers.removeIf(result) { item ->
                !originByVideoId.containsKey(item.videoId)
            }
        }
    }

    private fun getBrowseService2(): BrowseService2 = BrowseService2Wrapper
}
