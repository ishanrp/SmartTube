package com.liskovsoft.youtubeapi.rss

import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem
import com.liskovsoft.sharedutils.helpers.Helpers
import com.liskovsoft.sharedutils.rx.RxHelper
import com.liskovsoft.youtubeapi.app.nsigsolver.common.YouTubeInfoExtractor
import com.liskovsoft.youtubeapi.browse.v2.BrowseService2
import com.liskovsoft.youtubeapi.browse.v2.BrowseService2Wrapper
import com.liskovsoft.youtubeapi.service.data.YouTubeMediaGroup
import com.liskovsoft.youtubeapi.service.data.YouTubeMediaItem
import io.reactivex.Observable
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
    private const val FRESH_TTL_MS = 10 * 60 * 1000L
    private const val STALE_TTL_MS = 24 * 60 * 60 * 1000L
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
    private val networkGate = Semaphore(MAX_CONCURRENT_FETCHES)
    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @JvmStatic
    @JvmOverloads
    fun getFeed(vararg channelIds: String, type: Int = -1): MediaGroup? {
        val ids = channelIds.take(MAX_ITEMS)
        val items = fetchFeedsSafe(ids, scheduleStaleRefresh = true) ?: return null
        return buildGroup(items, type)
    }

    @JvmStatic
    fun getFeedObserve(vararg channelIds: String): Observable<MediaGroup> {
        return RxHelper.create { emitter ->
            val ids = channelIds.take(MAX_ITEMS)
            val staleIds = ids.filter { isUsableStale(it, System.currentTimeMillis()) }
            val initial = fetchFeedsSafe(ids, scheduleStaleRefresh = false)

            if (initial == null) {
                if (!emitter.isDisposed) {
                    RxHelper.onError(emitter, "RSS feed fetch failed")
                }
                return@create
            }

            if (!emitter.isDisposed) {
                emitter.onNext(buildGroup(initial, -1))
            }

            if (staleIds.isNotEmpty() && refreshChannels(staleIds) && !emitter.isDisposed) {
                emitter.onNext(buildGroup(collectCached(ids), -1))
            }

            if (!emitter.isDisposed) {
                emitter.onComplete()
            }
        }
    }

    @JvmStatic
    fun invalidate(vararg channelIds: String) {
        channelIds.take(MAX_ITEMS).forEach { channelId ->
            val cached = feedCache[channelId]
            if (cached != null) {
                feedCache[channelId] = cached.copy(fetchedAtMs = 0)
            }
            retryAfterMs.remove(channelId)
        }
    }

    private fun buildGroup(items: MutableList<MediaItem>, type: Int): MediaGroup {
        items.sortByDescending { it.publishedDate }

        return YouTubeMediaGroup(type).apply {
            mediaItems = items
        }
    }

    private fun fetchFeedsSafe(
        channelIds: List<String>,
        scheduleStaleRefresh: Boolean
    ): MutableList<MediaItem>? {
        try {
            return fetchFeeds(channelIds, scheduleStaleRefresh)
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }

        return null
    }

    private fun fetchFeeds(
        channelIds: List<String>,
        scheduleStaleRefresh: Boolean
    ): MutableList<MediaItem> = runBlocking {
        coroutineScope {
            channelIds
                .map { channelId ->
                    async {
                        fetchFeedCached(channelId, scheduleStaleRefresh)
                    }
                }
                .mapNotNull { it.await() }
                .flatten()
                .toMutableList()
        }
    }

    private suspend fun fetchFeedCached(
        channelId: String,
        scheduleStaleRefresh: Boolean
    ): List<MediaItem>? {
        val now = System.currentTimeMillis()
        val cached = feedCache[channelId]

        if (cached != null) {
            val age = now - cached.fetchedAtMs

            if (age <= FRESH_TTL_MS) {
                return cached.items
            }

            if (age <= STALE_TTL_MS) {
                if (scheduleStaleRefresh) {
                    scheduleRefresh(channelId)
                }
                return cached.items
            }
        }

        if (isBackedOff(channelId, now)) {
            return cached?.items
        }

        return channelLock(channelId).withLock {
            val lockedNow = System.currentTimeMillis()
            val lockedCached = feedCache[channelId]

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
                refreshChannel(channelId)
            } finally {
                refreshesInFlight.remove(channelId)
            }
        }
    }

    private fun refreshChannels(channelIds: List<String>): Boolean = runBlocking {
        coroutineScope {
            channelIds
                .map { channelId -> async { refreshChannel(channelId) } }
                .map { it.await() }
                .any { it }
        }
    }

    private suspend fun refreshChannel(channelId: String): Boolean {
        return channelLock(channelId).withLock {
            val now = System.currentTimeMillis()
            val cached = feedCache[channelId]

            if (cached != null && now - cached.fetchedAtMs <= FRESH_TTL_MS) {
                return@withLock false
            }

            if (isBackedOff(channelId, now)) {
                return@withLock false
            }

            val fresh = fetchFeedNetworkLimited(channelId)

            if (fresh != null) {
                storeFresh(channelId, fresh)
                true
            } else {
                markFailure(channelId)
                false
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

    private fun collectCached(channelIds: List<String>): MutableList<MediaItem> {
        return channelIds
            .mapNotNull { feedCache[it]?.items }
            .flatten()
            .toMutableList()
    }

    private fun isUsableStale(channelId: String, now: Long): Boolean {
        val cached = feedCache[channelId] ?: return false
        val age = now - cached.fetchedAtMs
        return age > FRESH_TTL_MS && age <= STALE_TTL_MS
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

        // Only filter RSS items when the channel response has enough overlap.
        if (matchedItems * 2 >= result.size) {
            Helpers.removeIf(result) { item ->
                !originByVideoId.containsKey(item.videoId)
            }
        }
    }

    private fun getBrowseService2(): BrowseService2 = BrowseService2Wrapper
}
