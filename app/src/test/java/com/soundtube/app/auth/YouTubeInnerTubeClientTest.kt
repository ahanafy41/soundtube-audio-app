package com.soundtube.app.auth

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeInnerTubeClientTest {

    @Test
    fun parseChannelsFromJson_extractsChannelRenderer() {
        val root = JSONObject("""
            {
                "contents": {
                    "twoColumnBrowseResultsRenderer": {
                        "tabs": [
                            {
                                "tabRenderer": {
                                    "content": {
                                        "sectionListRenderer": {
                                            "contents": [
                                                {
                                                    "itemSectionRenderer": {
                                                        "contents": [
                                                            {
                                                                "channelRenderer": {
                                                                    "channelId": "UC123456789",
                                                                    "title": {
                                                                        "simpleText": "Sample Channel Title"
                                                                    },
                                                                    "thumbnail": {
                                                                        "thumbnails": [
                                                                            { "url": "https://yt3.ggpht.com/avatar.jpg" }
                                                                        ]
                                                                    }
                                                                }
                                                            }
                                                        ]
                                                    }
                                                }
                                            ]
                                        }
                                    }
                                }
                            }
                        ]
                    }
                }
            }
        """.trimIndent())

        val channels = YouTubeInnerTubeClient.parseChannelsFromJson(root)

        assertEquals(1, channels.size)
        assertEquals("UC123456789", channels[0].channelId)
        assertEquals("Sample Channel Title", channels[0].channelTitle)
        assertEquals("https://yt3.ggpht.com/avatar.jpg", channels[0].avatarUrl)
    }

    @Test
    fun parseChannelsFromJson_supportsTitleRunsAndProtocolRelativeAvatar() {
        val root = JSONObject("""
            {
                "gridChannelRenderer": {
                    "channelId": "UC_GRID",
                    "title": {
                        "runs": [
                            { "text": "Part 1 " },
                            { "text": "Part 2" }
                        ]
                    },
                    "thumbnail": {
                        "thumbnails": [
                            { "url": "//yt3.ggpht.com/thumb.png" }
                        ]
                    }
                }
            }
        """.trimIndent())

        val channels = YouTubeInnerTubeClient.parseChannelsFromJson(root)

        assertEquals(1, channels.size)
        assertEquals("UC_GRID", channels[0].channelId)
        assertEquals("Part 1 Part 2", channels[0].channelTitle)
        assertEquals("https://yt3.ggpht.com/thumb.png", channels[0].avatarUrl)
    }

    @Test
    fun extractContinuationToken_findsTokenInTree() {
        val root = JSONObject("""
            {
                "contents": {
                    "continuationEndpoint": {
                        "continuationCommand": {
                            "token": "CONTINUATION_TOKEN_XYZ_123"
                        }
                    }
                }
            }
        """.trimIndent())

        val token = YouTubeInnerTubeClient.extractContinuationToken(root)
        assertEquals("CONTINUATION_TOKEN_XYZ_123", token)
    }

    @Test
    fun extractContinuationToken_returnsNullWhenNonePresent() {
        val root = JSONObject("""
            {
                "contents": {
                    "text": "no token here"
                }
            }
        """.trimIndent())

        val token = YouTubeInnerTubeClient.extractContinuationToken(root)
        assertNull(token)
    }

    @Test
    fun parseChannelsFromJson_extractsSubscriptionListItemRenderer() {
        val root = JSONObject("""
            {
                "contents": {
                    "sectionListRenderer": {
                        "contents": [
                            {
                                "itemSectionRenderer": {
                                    "contents": [
                                        {
                                            "subscriptionListItemRenderer": {
                                                "navigationEndpoint": {
                                                    "browseEndpoint": {
                                                        "browseId": "UC_SUB_LIST_123"
                                                    }
                                                },
                                                "title": {
                                                    "runs": [
                                                        { "text": "Sub List Channel" }
                                                    ]
                                                },
                                                "thumbnail": {
                                                    "thumbnails": [
                                                        { "url": "https://yt3.ggpht.com/sub_avatar.jpg" }
                                                    ]
                                                }
                                            }
                                        }
                                    ]
                                }
                            }
                        ]
                    }
                }
            }
        """.trimIndent())

        val channels = YouTubeInnerTubeClient.parseChannelsFromJson(root)

        assertEquals(1, channels.size)
        assertEquals("UC_SUB_LIST_123", channels[0].channelId)
        assertEquals("Sub List Channel", channels[0].channelTitle)
        assertEquals("https://yt3.ggpht.com/sub_avatar.jpg", channels[0].avatarUrl)
    }

    @Test
    fun parseChannelsFromJson_extractsGuideEntryRenderer() {
        val root = JSONObject("""
            {
                "items": [
                    {
                        "guideSubscriptionsSectionRenderer": {
                            "items": [
                                {
                                    "guideEntryRenderer": {
                                        "navigationEndpoint": {
                                            "browseEndpoint": {
                                                "browseId": "UC_GUIDE_456"
                                            }
                                        },
                                        "formattedTitle": {
                                            "simpleText": "Guide Subscribed Channel"
                                        },
                                        "thumbnail": {
                                            "thumbnails": [
                                                { "url": "https://yt3.ggpht.com/guide_avatar.jpg" }
                                            ]
                                        }
                                    }
                                },
                                {
                                    "guideEntryRenderer": {
                                        "navigationEndpoint": {
                                            "browseEndpoint": {
                                                "browseId": "FEwhat_to_watch"
                                            }
                                        },
                                        "formattedTitle": {
                                            "simpleText": "Home"
                                        }
                                    }
                                }
                            ]
                        }
                    }
                ]
            }
        """.trimIndent())

        val channels = YouTubeInnerTubeClient.parseChannelsFromJson(root)

        assertEquals(1, channels.size)
        assertEquals("UC_GUIDE_456", channels[0].channelId)
        assertEquals("Guide Subscribed Channel", channels[0].channelTitle)
        assertEquals("https://yt3.ggpht.com/guide_avatar.jpg", channels[0].avatarUrl)
    }

    @Test
    fun parseChannelsFromJson_extractsCompactChannelRenderer() {
        val root = JSONObject("""
            {
                "compactChannelRenderer": {
                    "channelId": "UC_COMPACT_789",
                    "title": {
                        "simpleText": "Compact Channel"
                    },
                    "thumbnail": {
                        "thumbnails": [
                            { "url": "https://yt3.ggpht.com/compact.jpg" }
                        ]
                    }
                }
            }
        """.trimIndent())

        val channels = YouTubeInnerTubeClient.parseChannelsFromJson(root)

        assertEquals(1, channels.size)
        assertEquals("UC_COMPACT_789", channels[0].channelId)
        assertEquals("Compact Channel", channels[0].channelTitle)
    }

    @Test
    fun parseChannelsFromJson_extractsCanonicalBaseUrlWhenBrowseIdMissing() {
        val root = JSONObject("""
            {
                "guideEntryRenderer": {
                    "navigationEndpoint": {
                        "browseEndpoint": {
                            "canonicalBaseUrl": "/@MyAwesomeChannel"
                        }
                    },
                    "title": {
                        "simpleText": "Handle Channel"
                    }
                }
            }
        """.trimIndent())

        val channels = YouTubeInnerTubeClient.parseChannelsFromJson(root)

        assertEquals(1, channels.size)
        assertEquals("@MyAwesomeChannel", channels[0].channelId)
        assertEquals("Handle Channel", channels[0].channelTitle)
    }

    @Test
    fun parseChannelsFromJson_ignoresSystemTopicChannels() {
        val root = JSONObject("""
            {
                "items": [
                    {
                        "channelRenderer": {
                            "channelId": "UC-9-kyTW8ZkZNDHQJ6FgpwQ",
                            "title": { "simpleText": "YouTube Music" }
                        }
                    },
                    {
                        "channelRenderer": {
                            "channelId": "UCOpNcN46UbXVtpKMrmU4Abg",
                            "title": { "simpleText": "YouTube Gaming" }
                        }
                    },
                    {
                        "channelRenderer": {
                            "channelId": "UCEgdi0XIXXZ-qJOFPf4JSKw",
                            "title": { "simpleText": "YouTube Sports" }
                        }
                    },
                    {
                        "channelRenderer": {
                            "channelId": "UC_REAL_USER_SUB",
                            "title": { "simpleText": "Actual User Channel" }
                        }
                    }
                ]
            }
        """.trimIndent())

        val channels = YouTubeInnerTubeClient.parseChannelsFromJson(root)

        assertEquals(1, channels.size)
        assertEquals("UC_REAL_USER_SUB", channels[0].channelId)
        assertEquals("Actual User Channel", channels[0].channelTitle)
    }

    @Test
    fun parseVideosFromJson_extractsVideoRenderer() {
        val root = JSONObject("""
            {
                "contents": {
                    "twoColumnBrowseResultsRenderer": {
                        "tabs": [
                            {
                                "tabRenderer": {
                                    "content": {
                                        "richGridRenderer": {
                                            "contents": [
                                                {
                                                    "richItemRenderer": {
                                                        "content": {
                                                            "videoRenderer": {
                                                                "videoId": "TEST_VID_001",
                                                                "title": {
                                                                    "runs": [
                                                                        { "text": "درس تاريخي شيق" }
                                                                    ]
                                                                },
                                                                "shortBylineText": {
                                                                    "runs": [
                                                                        {
                                                                            "text": "قناة التاريخ والآداب",
                                                                            "navigationEndpoint": {
                                                                                "browseEndpoint": {
                                                                                    "browseId": "UC_HISTORY_123"
                                                                                }
                                                                            }
                                                                        }
                                                                    ]
                                                                },
                                                                "lengthText": {
                                                                    "simpleText": "14:25"
                                                                },
                                                                "publishedTimeText": {
                                                                    "simpleText": "منذ يومين"
                                                                },
                                                                "thumbnail": {
                                                                    "thumbnails": [
                                                                        { "url": "https://i.ytimg.com/vi/TEST_VID_001/hqdefault.jpg" }
                                                                    ]
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            ]
                                        }
                                    }
                                }
                            }
                        ]
                    }
                }
            }
        """.trimIndent())

        val videos = YouTubeInnerTubeClient.parseVideosFromJson(root)

        assertEquals(1, videos.size)
        val v = videos[0]
        assertEquals("TEST_VID_001", v.videoId)
        assertEquals("درس تاريخي شيق", v.title)
        assertEquals("قناة التاريخ والآداب", v.uploaderName)
        assertEquals("UC_HISTORY_123", v.uploaderUrl)
        assertEquals("14:25", v.durationText)
        assertEquals(14 * 60 + 25L, v.durationSeconds)
        assertEquals("منذ يومين", v.uploadDate)
        assertEquals("https://i.ytimg.com/vi/TEST_VID_001/hqdefault.jpg", v.thumbnailUrl)
    }

    @Test
    fun parseVideosFromJson_extractsCompactAndGridVideoRenderer() {
        val root = JSONObject("""
            {
                "contents": [
                    {
                        "compactVideoRenderer": {
                            "videoId": "COMPACT_01",
                            "title": { "simpleText": "Compact Audio" },
                            "ownerText": { "runs": [{ "text": "Voice Channel" }] },
                            "lengthText": { "simpleText": "03:10" }
                        }
                    },
                    {
                        "gridVideoRenderer": {
                            "videoId": "GRID_02",
                            "title": { "simpleText": "Grid Lecture" },
                            "shortBylineText": { "simpleText": "Lectures Channel" },
                            "lengthText": { "simpleText": "01:05:00" }
                        }
                    }
                ]
            }
        """.trimIndent())

        val videos = YouTubeInnerTubeClient.parseVideosFromJson(root)

        assertEquals(2, videos.size)
        assertEquals("COMPACT_01", videos[0].videoId)
        assertEquals("Compact Audio", videos[0].title)
        assertEquals(190L, videos[0].durationSeconds)

        assertEquals("GRID_02", videos[1].videoId)
        assertEquals("Grid Lecture", videos[1].title)
        assertEquals(3900L, videos[1].durationSeconds)
    }

    @Test
    fun parseVideosFromJson_performance_benchmark() {
        val array = JSONArray()
        for (i in 1..50) {
            val video = JSONObject()
            video.put("videoId", "BENCH_VID_$i")
            val titleObj = JSONObject()
            titleObj.put("simpleText", "Benchmark Video Item #$i")
            video.put("title", titleObj)
            val bylineObj = JSONObject()
            bylineObj.put("simpleText", "Benchmark Creator")
            video.put("shortBylineText", bylineObj)
            val lenObj = JSONObject()
            lenObj.put("simpleText", "10:00")
            video.put("lengthText", lenObj)

            val container = JSONObject()
            container.put("videoRenderer", video)
            array.put(container)
        }
        val root = JSONObject()
        root.put("items", array)

        val startTime = System.currentTimeMillis()
        val parsed = YouTubeInnerTubeClient.parseVideosFromJson(root)
        val elapsed = System.currentTimeMillis() - startTime

        assertEquals(50, parsed.size)
        // Parsing 50 videos in-memory should take less than 150ms
        org.junit.Assert.assertTrue("Parsing 50 videos took $elapsed ms (expected < 150ms)", elapsed < 150)
    }
}
