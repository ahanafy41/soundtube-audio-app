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
}
