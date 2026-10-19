package com.soundtube.app.cache

import com.soundtube.app.data.model.AudioStreamInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class StreamCachePerformanceTest {

    @Test
    fun streamCache_storesAndRetrievesWithinTtl() {
        val cache = ConcurrentHashMap<String, Pair<AudioStreamInfo, Long>>()
        val ttlMs = 2 * 60 * 60 * 1000L // 2 hours

        val sampleStream = AudioStreamInfo(
            videoId = "TEST_AUDIO_101",
            title = "قصيدة صوتية رائعة",
            channelTitle = "قناة الأدب العربي",
            channelId = "UC_ADAB_01",
            streamUrl = "https://rr1---sn-4g5ednle.googlevideo.com/videoplayback?id=101",
            format = "M4A",
            bitrate = 128000,
            durationSeconds = 240,
            thumbnailUrl = null
        )

        val now = System.currentTimeMillis()
        cache[sampleStream.videoId] = Pair(sampleStream, now)

        val entry = cache["TEST_AUDIO_101"]
        assertNotNull(entry)
        assertTrue((System.currentTimeMillis() - entry!!.second) < ttlMs)
        assertEquals("قصيدة صوتية رائعة", entry.first.title)
        assertEquals(128000, entry.first.bitrate)
    }

    @Test
    fun streamCache_expiresEntriesAfterTtl() {
        val cache = ConcurrentHashMap<String, Pair<AudioStreamInfo, Long>>()
        val ttlMs = 1000L // 1 second for test

        val sampleStream = AudioStreamInfo(
            videoId = "TEST_EXPIRED",
            title = "مقطع منتهي الصلاحية",
            channelTitle = "قناة تجريبية",
            channelId = "UC_EXP",
            streamUrl = "https://googlevideo.com/playback",
            format = "M4A",
            bitrate = 64000,
            durationSeconds = 60,
            thumbnailUrl = null
        )

        // Store with timestamp 2 seconds in the past
        val pastTimestamp = System.currentTimeMillis() - 2000L
        cache[sampleStream.videoId] = Pair(sampleStream, pastTimestamp)

        val entry = cache["TEST_EXPIRED"]
        assertNotNull(entry)
        val isExpired = (System.currentTimeMillis() - entry!!.second) >= ttlMs
        assertTrue("Entry should be expired", isExpired)
    }

    @Test
    fun streamCache_concurrentMultiThreadedAccessPerformance() {
        val cache = ConcurrentHashMap<String, Pair<AudioStreamInfo, Long>>()
        val threadCount = 10
        val operationsPerThread = 500
        val latch = CountDownLatch(threadCount)
        val executor = Executors.newFixedThreadPool(threadCount)

        val startTime = System.currentTimeMillis()

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    for (i in 0 until operationsPerThread) {
                        val vid = "VID_${t}_$i"
                        val info = AudioStreamInfo(
                            videoId = vid,
                            title = "Item $vid",
                            channelTitle = "Channel $t",
                            channelId = "CH_$t",
                            streamUrl = "https://googlevideo.com/$vid",
                            format = "M4A",
                            bitrate = 128000,
                            durationSeconds = 120,
                            thumbnailUrl = null
                        )
                        cache[vid] = Pair(info, System.currentTimeMillis())
                        val retrieved = cache[vid]
                        assertNotNull(retrieved)
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        val completed = latch.await(5, TimeUnit.SECONDS)
        val duration = System.currentTimeMillis() - startTime
        executor.shutdown()

        assertTrue("All concurrent threads should complete successfully", completed)
        assertEquals(threadCount * operationsPerThread, cache.size)
        // 5000 concurrent cache operations should finish in well under 1000ms
        assertTrue("Concurrent caching was too slow: $duration ms", duration < 1000)
    }
}
