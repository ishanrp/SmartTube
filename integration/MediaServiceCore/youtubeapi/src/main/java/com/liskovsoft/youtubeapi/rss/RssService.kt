package com.liskovsoft.youtubeapi.rss

import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem
import com.liskovsoft.sharedutils.helpers.Helpers
import com.liskovsoft.sharedutils.okhttp.OkHttpManager
import com.liskovsoft.sharedutils.rx.RxHelper
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
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

internal object RssService {
    private const val RSS_URL: String = "https://www.youtube.com/feeds/videos.xml?channel_id="
    private const val MAX_ITEMS = 100
    private const val MAX_MEMORY_CACHE_ENTRIES = 8
    private const val LOCK_STRIPES = 32
    private const val CACHE_SCHEMA_VERSION = 2
    private const val FRESH_TTL_MS = 10 * 60 * 1000L
    private const val STALE_TTL_MS = 7 * 24 * 60 * 60 * 1000L
    private const val DISK_RETENTION_MS = 30 * 24 * 60 * 60 * 1000L
    private const val FAILURE_BACKOFF_MS = 5 * 60 * 1000L

    private val feedCacheLock = Any()
    private val feedCache = object : LinkedHashMap<String, RssCacheEntry>(
        MAX_MEMORY_CACHE_ENTRIES,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, RssCacheEntry>?
        ): Boolean = size > MAX_MEMORY_CACHE_ENTRIES
    }
    private val retryAfterMs = ConcurrentHashMap<String, Long>()
    private val channelLocks = Array(LOCK_STRIPES) { Mutex() }
    private val refreshesInFlight =
        Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val diskCache = PersistentContentCache(
        "channel-feed",
        RssCacheEntry::class.java,
        DISK_RETENTION_MS
    )
    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeRequests = AtomicInteger()
    private val queuedRequests = AtomicInteger()

    @Volatile
    private var networkGate = Semaphore(RssRuntimeState.getParallelRequests())

    init {
        refreshScope.launch {
            diskCache.prune()
        }
    }

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
            val now = System.currentTimeMillis()

            val staleIds = ids.filter { channelId ->
                val cached = loadCache(channelId)
                cached != null && isUsableStale(cached, now)
            }

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
            val cached = loadCache(channelId)

            if (cached != null) {
                val invalidated = cached.copy(fetchedAtMs = 0)
                memoryPut(channelId, invalidated)
                diskCache.save(channelId, invalidated)
            }

            retryAfterMs.remove(channelId)
        }

        RssRuntimeState.addEvent("Manual feed refresh requested")
    }

    @JvmStatic
    fun clearCache() {
        memoryClear()
        retryAfterMs.clear()
        diskCache.clear()
        RssRuntimeState.addEvent("Subscription feed cache cleared")
    }

    @JvmStatic
    fun getParallelRequests(): Int = RssRuntimeState.getParallelRequests()

    @JvmStatic
    fun setParallelRequests(value: Int) {
        val limit = RssRuntimeState.setParallelRequests(value)
        networkGate = Semaphore(limit)
    }

    @JvmStatic
    fun getStatus(): String = RssRuntimeState.status()

    @JvmStatic
    fun getBackoffRemainingMs(): Long = RssRuntimeState.backoffRemainingMs()

    @JvmStatic
    fun getDiagnostics(): RssDiagnostics {
        val now = System.currentTimeMillis()
        val diskStats = diskCache.stats(FRESH_TTL_MS, STALE_TTL_MS, now)

        return RssDiagnostics(
            status = RssRuntimeState.status(now),
            lastIssue = RssRuntimeState.lastIssue(),
            backoffRemainingMs = RssRuntimeState.backoffRemainingMs(now),
            backoffLevel = RssRuntimeState.backoffLevel(),
            parallelRequests = RssRuntimeState.getParallelRequests(),
            activeRequests = activeRequests.get(),
            queuedRequests = queuedRequests.get(),
            lastSuccessfulFetchMs = RssRuntimeState.lastSuccessfulFetchMs(),
            feedMode = RssRuntimeState.feedMode(),
            memoryEntries = memorySize(),
            diskEntries = diskStats.entryCount,
            freshEntries = diskStats.freshEntries,
            staleEntries = diskStats.staleEntries,
            veryStaleEntries = diskStats.veryStaleEntries,
            diskBytes = diskStats.bytes,
            http429Failures = RssRuntimeState.http429Failures(),
            timeoutFailures = RssRuntimeState.timeoutFailures(),
            otherFailures = RssRuntimeState.otherFailures(),
            recentEvents = RssRuntimeState.recentEvents()
        )
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
        val result = mutableListOf<MediaItem>()
        val batchSize = RssRuntimeState.getParallelRequests().coerceAtLeast(1)

        for (batch in channelIds.chunked(batchSize)) {
            val batchItems = coroutineScope {
                batch
                    .map { channelId ->
                        async {
                            fetchFeedCached(channelId, scheduleStaleRefresh)
                        }
                    }
                    .mapNotNull { it.await() }
            }

            batchItems.forEach { result.addAll(it) }
        }

        result
    }

    private suspend fun fetchFeedCached(
        channelId: String,
        scheduleStaleRefresh: Boolean
    ): List<MediaItem>? {
        val now = System.currentTimeMillis()
        val cached = loadCache(channelId)

        if (cached != null) {
            val age = now - cached.fetchedAtMs

            if (age <= FRESH_TTL_MS) {
                RssRuntimeState.recordFeedMode("Memory/disk cache")
                return materialize(cached)
            }

            if (age <= STALE_TTL_MS) {
                RssRuntimeState.recordFeedMode("Stale cache")

                if (scheduleStaleRefresh) {
                    scheduleRefresh(channelId)
                }

                return materialize(cached)
            }
        }

        if (!RssRuntimeState.canRequest(now)) {
            RssRuntimeState.recordFeedMode("Cached during backoff")
            return cached?.let(::materialize)
        }

        if (isBackedOff(channelId, now)) {
            return cached?.let(::materialize)
        }

        return channelLock(channelId).withLock {
            val lockedNow = System.currentTimeMillis()
            val lockedCached = loadCache(channelId)

            if (lockedCached != null && lockedNow - lockedCached.fetchedAtMs <= FRESH_TTL_MS) {
                return@withLock materialize(lockedCached)
            }

            if (!RssRuntimeState.canRequest(lockedNow) || isBackedOff(channelId, lockedNow)) {
                return@withLock lockedCached?.let(::materialize)
            }

            val fresh = fetchFeedNetworkLimited(channelId)

            if (fresh != null && storeFresh(channelId, fresh, lockedCached)) {
                fresh
            } else {
                if (RssRuntimeState.canRequest()) {
                    markFailure(channelId)
                }
                lockedCached?.let(::materialize)
            }
        }
    }

    private fun scheduleRefresh(channelId: String) {
        val now = System.currentTimeMillis()

        if (!RssRuntimeState.canRequest(now) ||
            isBackedOff(channelId, now) ||
            !refreshesInFlight.add(channelId)) {
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
        val batchSize = RssRuntimeState.getParallelRequests().coerceAtLeast(1)
        var refreshed = false

        for (batch in channelIds.chunked(batchSize)) {
            val batchRefreshed = coroutineScope {
                batch
                    .map { channelId -> async { refreshChannel(channelId) } }
                    .map { it.await() }
            }

            if (batchRefreshed.any { it }) {
                refreshed = true
            }

            if (!RssRuntimeState.canRequest()) {
                break
            }
        }

        refreshed
    }

    private suspend fun refreshChannel(channelId: String): Boolean {
        return channelLock(channelId).withLock {
            val now = System.currentTimeMillis()
            val cached = loadCache(channelId)

            if (cached != null && now - cached.fetchedAtMs <= FRESH_TTL_MS) {
                return@withLock false
            }

            if (!RssRuntimeState.canRequest(now) || isBackedOff(channelId, now)) {
                return@withLock false
            }

            val fresh = fetchFeedNetworkLimited(channelId)

            if (fresh != null && storeFresh(channelId, fresh, cached)) {
                true
            } else {
                if (RssRuntimeState.canRequest()) {
                    markFailure(channelId)
                }
                false
            }
        }
    }

    private suspend fun fetchFeedNetworkLimited(channelId: String): List<MediaItem>? {
        if (!RssRuntimeState.canRequest()) {
            return null
        }

        val gate = networkGate
        queuedRequests.incrementAndGet()
        var removedFromQueue = false
        var acquired = false

        try {
            gate.acquire()
            acquired = true
            queuedRequests.decrementAndGet()
            removedFromQueue = true

            if (!RssRuntimeState.canRequest()) {
                return null
            }

            activeRequests.incrementAndGet()

            try {
                return fetchFeedNetwork(channelId)
            } finally {
                activeRequests.decrementAndGet()
            }
        } finally {
            if (!removedFromQueue) {
                queuedRequests.decrementAndGet()
            }

            if (acquired) {
                gate.release()
            }
        }
    }

    private suspend fun fetchFeedNetwork(channelId: String): List<MediaItem>? =
        withContext(Dispatchers.IO) {
            try {
                val rssContent = downloadRss(channelId)
                val result = YouTubeRssParser(Helpers.toStream(rssContent)).parse()

                RssRuntimeState.recordSuccess()

                try {
                    syncWithChannel(channelId, result)
                } catch (e: Exception) {
                    RssRuntimeState.addEvent("Channel enrichment failed")
                    e.printStackTrace()
                }

                RssRuntimeState.recordFeedMode("Network")
                result
            } catch (e: RssHttpException) {
                RssRuntimeState.recordHttpFailure(e.statusCode)
                e.printStackTrace()
                null
            } catch (e: SocketTimeoutException) {
                RssRuntimeState.recordTimeout()
                e.printStackTrace()
                null
            } catch (e: IOException) {
                RssRuntimeState.recordFailure("Network error")
                e.printStackTrace()
                null
            } catch (e: Exception) {
                RssRuntimeState.recordFailure("RSS parse/error")
                e.printStackTrace()
                null
            }
        }

    private fun downloadRss(channelId: String): String {
        val request = Request.Builder().url(RSS_URL + channelId).build()

        return OkHttpManager.instance().client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw RssHttpException(response.code())
            }

            response.body()?.string() ?: throw IOException("Empty RSS response")
        }
    }

    private fun storeFresh(
        channelId: String,
        items: List<MediaItem>,
        previous: RssCacheEntry?
    ): Boolean {
        if (items.isEmpty() && previous != null && previous.items.isNotEmpty()) {
            RssRuntimeState.addEvent("Ignored empty feed refresh")
            return false
        }

        val entry = RssCacheEntry(
            schemaVersion = CACHE_SCHEMA_VERSION,
            fetchedAtMs = System.currentTimeMillis(),
            items = items.map(CachedRssItem::fromMediaItem)
        )

        memoryPut(channelId, entry)
        diskCache.save(channelId, entry)
        retryAfterMs.remove(channelId)

        return true
    }

    private fun collectCached(channelIds: List<String>): MutableList<MediaItem> {
        val result = mutableListOf<MediaItem>()

        channelIds.forEach { channelId ->
            loadCache(channelId)?.let { result.addAll(materialize(it)) }
        }

        return result
    }

    private fun loadCache(channelId: String): RssCacheEntry? {
        memoryGet(channelId)?.let { return it }

        val disk = diskCache.load(channelId) ?: return null

        if (disk.schemaVersion != CACHE_SCHEMA_VERSION) {
            diskCache.delete(channelId)
            return null
        }

        memoryPut(channelId, disk)
        return disk
    }

    private fun memoryGet(channelId: String): RssCacheEntry? =
        synchronized(feedCacheLock) {
            feedCache[channelId]
        }

    private fun memoryPut(channelId: String, entry: RssCacheEntry) {
        synchronized(feedCacheLock) {
            feedCache[channelId] = entry
        }
    }

    private fun memoryClear() {
        synchronized(feedCacheLock) {
            feedCache.clear()
        }
    }

    private fun memorySize(): Int =
        synchronized(feedCacheLock) {
            feedCache.size
        }

    private fun materialize(entry: RssCacheEntry): List<MediaItem> {
        return entry.items.map(CachedRssItem::toMediaItem)
    }

    private fun isUsableStale(entry: RssCacheEntry, now: Long): Boolean {
        val age = now - entry.fetchedAtMs
        return age > FRESH_TTL_MS && age <= STALE_TTL_MS
    }

    private fun markFailure(channelId: String) {
        retryAfterMs[channelId] = System.currentTimeMillis() + FAILURE_BACKOFF_MS
    }

    private fun isBackedOff(channelId: String, now: Long): Boolean =
        (retryAfterMs[channelId] ?: 0L) > now

    private fun channelLock(channelId: String): Mutex {
        val index = (channelId.hashCode() and Int.MAX_VALUE) % LOCK_STRIPES
        return channelLocks[index]
    }

    /**
     * Add missing props and remove shorts etc.
     */
    private fun syncWithChannel(channelId: String, result: List<MediaItem>) {
        val group = getBrowseService2().getChannelAsGrid(channelId)
        val originItems = group?.mediaItems ?: return

        Helpers.removeIf(result) { item ->
            val first = originItems.firstOrNull { it?.videoId == item.videoId }
            if (first != null) {
                item as YouTubeMediaItem
                item.badgeText = first.badgeText
                item.isLive = first.isLive
                item.isUpcoming = first.isUpcoming
                item.videoPreviewUrl = first.videoPreviewUrl
                item.percentWatched = first.percentWatched

                return@removeIf false
            }

            return@removeIf true
        }
    }

    private fun getBrowseService2(): BrowseService2 = BrowseService2Wrapper

    private class RssHttpException(val statusCode: Int) :
        IOException("Unexpected RSS HTTP status $statusCode")
}
