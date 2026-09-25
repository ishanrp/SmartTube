package com.liskovsoft.youtubeapi.rss

import com.liskovsoft.youtubeapi.service.data.YouTubeMediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RssCacheModelsTest {
    @Test
    fun cachedItemRoundTripsSourceNeutralFields() {
        val source = YouTubeMediaItem().apply {
            setTitle("Title")
            setSecondTitle("Author · 1K views · 1 day ago")
            setVideoId("video-id")
            setChannelId("channel-id")
            setCardImageUrl("https://example.invalid/thumb.jpg")
            setPublishedDate(123456789L)
            setUpdatedDate(123456999L)
            setAuthor("Author")
            setDescription("Description")
            setViewCount(1234)
            setBadgeText("12:34")
            setLive(true)
            setUpcoming(false)
            setVideoPreviewUrl("https://example.invalid/preview.jpg")
            setPercentWatched(73)
        }

        val cached = CachedRssItem.fromMediaItem(source)
        val restored = cached.toMediaItem() as YouTubeMediaItem

        assertEquals(source.title, restored.title)
        assertEquals(source.secondTitle.toString(), restored.secondTitle.toString())
        assertEquals(source.videoId, restored.videoId)
        assertEquals(source.channelId, restored.channelId)
        assertEquals(source.cardImageUrl, restored.cardImageUrl)
        assertEquals(source.publishedDate, restored.publishedDate)
        assertEquals(source.author, restored.author)
        assertEquals(source.badgeText, restored.badgeText)
        assertTrue(restored.isLive)
        assertFalse(restored.isUpcoming)

        // Optional detail/profile state is deliberately not persisted in the channel cache.
        assertEquals(0, restored.updatedDate)
        assertEquals(null, restored.description)
        assertEquals(0, restored.viewCount)
        assertEquals(-1, restored.percentWatched)
    }
}
