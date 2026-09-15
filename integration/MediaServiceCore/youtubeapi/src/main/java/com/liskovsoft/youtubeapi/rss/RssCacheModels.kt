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
    val updatedDate: Long,
    val author: String?,
    val description: String?,
    val viewCount: Int,
    val badgeText: String?,
    val isLive: Boolean,
    val isUpcoming: Boolean,
    val videoPreviewUrl: String?
) {
    fun toMediaItem(): MediaItem {
        return YouTubeMediaItem().apply {
            setTitle(title)
            setSecondTitle(secondTitle)
            setVideoId(videoId)
            setChannelId(channelId)
            setCardImageUrl(cardImageUrl)
            setPublishedDate(publishedDate)
            setUpdatedDate(updatedDate)
            setAuthor(author)
            setDescription(description)
            setViewCount(viewCount)
            setBadgeText(badgeText)
            setLive(isLive)
            setUpcoming(isUpcoming)
            setVideoPreviewUrl(videoPreviewUrl)
        }
    }

    companion object {
        fun fromMediaItem(item: MediaItem): CachedRssItem {
            val youtubeItem = item as? YouTubeMediaItem

            return CachedRssItem(
                title = item.title,
                secondTitle = item.secondTitle?.toString(),
                videoId = item.videoId,
                channelId = item.channelId,
                cardImageUrl = item.cardImageUrl,
                publishedDate = item.publishedDate,
                updatedDate = youtubeItem?.updatedDate ?: 0,
                author = item.author,
                description = youtubeItem?.description,
                viewCount = youtubeItem?.viewCount ?: 0,
                badgeText = item.badgeText,
                isLive = item.isLive,
                isUpcoming = item.isUpcoming,
                videoPreviewUrl = item.videoPreviewUrl
            )
        }
    }
}
