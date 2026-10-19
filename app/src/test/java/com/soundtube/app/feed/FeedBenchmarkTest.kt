package com.soundtube.app.feed

import com.soundtube.app.data.local.FeedCacheEntity
import com.soundtube.app.data.model.VideoFeedItem
import com.soundtube.app.data.model.parseUploadDateScore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedBenchmarkTest {

    @Test
    fun feedEntityMapping_performanceAndIntegrity() {
        val count = 200
        val entities = (1..count).map { i ->
            FeedCacheEntity(
                videoId = "CACHE_VID_$i",
                title = "عنوان المقطع رقم $i",
                uploaderName = "الناشر $i",
                uploaderUrl = "https://youtube.com/channel/CH_$i",
                durationText = "12:30",
                durationSeconds = 750,
                thumbnailUrl = "https://img.youtube.com/vi/CACHE_VID_$i/0.jpg",
                uploadDate = "منذ ${i % 30} أيام",
                cachedAt = System.currentTimeMillis()
            )
        }

        val startTime = System.currentTimeMillis()
        val feedItems = entities.map { entity ->
            VideoFeedItem(
                videoId = entity.videoId,
                title = entity.title,
                uploaderName = entity.uploaderName,
                uploaderUrl = entity.uploaderUrl,
                durationText = entity.durationText,
                durationSeconds = entity.durationSeconds,
                thumbnailUrl = entity.thumbnailUrl,
                uploadDate = entity.uploadDate
            )
        }
        val elapsed = System.currentTimeMillis() - startTime

        assertEquals(count, feedItems.size)
        assertEquals("CACHE_VID_1", feedItems[0].videoId)
        assertEquals("عنوان المقطع رقم 1", feedItems[0].title)
        assertTrue("Mapping 200 feed items took $elapsed ms (expected < 50ms)", elapsed < 50)
    }

    @Test
    fun feedSorting_newestFirstPrioritization() {
        val items = listOf(
            VideoFeedItem(videoId = "1", title = "قديم", uploaderName = "قناة", uploadDate = "منذ سنة"),
            VideoFeedItem(videoId = "2", title = "الآن", uploaderName = "قناة", uploadDate = "منذ ساعتين"),
            VideoFeedItem(videoId = "3", title = "أمس", uploaderName = "قناة", uploadDate = "منذ يوم واحد"),
            VideoFeedItem(videoId = "4", title = "أسبوع", uploaderName = "قناة", uploadDate = "منذ أسبوع")
        )

        val sorted = items.sortedWith(compareByDescending { parseUploadDateScore(it.uploadDate) })

        // "منذ ساعتين" should be highest score (newest), then "منذ يوم واحد", then "منذ أسبوع", then "منذ سنة"
        assertEquals("2", sorted[0].videoId)
        assertEquals("3", sorted[1].videoId)
        assertEquals("4", sorted[2].videoId)
        assertEquals("1", sorted[3].videoId)
    }
}
