package com.liskovsoft.youtubeapi.rss

import com.liskovsoft.mediaserviceinterfaces.data.MediaItem
import com.liskovsoft.youtubeapi.service.data.YouTubeMediaItem

internal data class RssCacheEntry(
    val schemaVersion: Int,
    val fetchedAtMs: Long,
    val items: List<CachedRssItem>
)

internal data class CachedRssItem(
    val title: String?,
    val secondTitle: String?,
    val videoId: String?,
    val channelId: String?,
    val cardImageUrl: String?,
    val publishedDate: Long,
    val author: String?,
    val badgeText: String?,
    val isLive: Boolean,
    val isUpcoming: Boolean,
    val videoPreviewUrl: String?
) {
    fun toMediaItem(): MediaItem {
        return YouTubeMediaItem().apply {
            setTitle(this@CachedRssItem.title)
            setSecondTitle(this@CachedRssItem.secondTitle)
            setVideoId(this@CachedRssItem.videoId)
            setChannelId(this@CachedRssItem.channelId)
            setCardImageUrl(this@CachedRssItem.cardImageUrl)
            setPublishedDate(this@CachedRssItem.publishedDate)
            setAuthor(this@CachedRssItem.author)
            setBadgeText(this@CachedRssItem.badgeText)
            setLive(this@CachedRssItem.isLive)
            setUpcoming(this@CachedRssItem.isUpcoming)
            setVideoPreviewUrl(this@CachedRssItem.videoPreviewUrl)
        }
    }

    companion object {
        fun fromMediaItem(item: MediaItem): CachedRssItem {
            return CachedRssItem(
                title = item.title,
                secondTitle = item.secondTitle?.toString(),
                videoId = item.videoId,
                channelId = item.channelId,
                cardImageUrl = item.cardImageUrl,
                publishedDate = item.publishedDate,
                author = item.author,
                badgeText = item.badgeText,
                isLive = item.isLive,
                isUpcoming = item.isUpcoming,
                videoPreviewUrl = item.videoPreviewUrl
            )
        }
    }
}
