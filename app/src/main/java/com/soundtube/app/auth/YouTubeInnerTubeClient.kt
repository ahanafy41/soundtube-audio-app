package com.soundtube.app.auth

import com.soundtube.app.data.local.Subscription
import com.soundtube.app.data.model.VideoFeedItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * YouTube InnerTube API client for fetching user subscriptions via FEchannels browse endpoint.
 * Authenticates using dynamic SAPISIDHASH signatures and session cookies.
 */
class YouTubeInnerTubeClient(
    private val httpClient: OkHttpClient = defaultHttpClient()
) {

    suspend fun fetchHomeFeed(
        cookieHeader: String,
        sapisid: String,
        browseId: String = "FEwhat_to_watch"
    ): Result<List<VideoFeedItem>> = withContext(Dispatchers.IO) {
        try {
            val payloadJson = buildRequestBody(null, browseId)
            val authHeader = SapisidHashGenerator.generate(sapisid)
            val endpointUrl = "$BROWSE_ENDPOINT?key=$INNERTUBE_API_KEY&prettyPrint=false"

            val request = Request.Builder()
                .url(endpointUrl)
                .post(payloadJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                .header("Content-Type", "application/json")
                .header("User-Agent", CHROME_MOBILE_UA)
                .header("Cookie", cookieHeader)
                .header("Authorization", authHeader)
                .header("Origin", "https://www.youtube.com")
                .header("Referer", "https://www.youtube.com/")
                .header("X-Origin", "https://www.youtube.com")
                .header("X-Goog-AuthUser", "0")
                .header("X-Youtube-Client-Name", "1")
                .header("X-Youtube-Client-Version", CLIENT_VERSION)
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                val code = response.code
                val errorBody = response.body?.string().orEmpty()
                response.close()
                return@withContext Result.failure(
                    IOException("فشل جلب خلاصة المقترحات: كود $code - $errorBody")
                )
            }

            val responseText = response.body?.string().orEmpty()
            response.close()

            if (responseText.isBlank()) {
                return@withContext Result.success(emptyList())
            }

            val jsonResponse = JSONObject(responseText)
            val videos = parseVideosFromJson(jsonResponse)
            Result.success(videos)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchAllSubscriptions(
        cookieHeader: String,
        sapisid: String
    ): Result<List<Subscription>> = withContext(Dispatchers.IO) {
        try {
            val subscriptions = mutableListOf<Subscription>()
            val seenChannelIds = mutableSetOf<String>()
            val seenTokens = mutableSetOf<String>()

            // Engine 1: Direct HTML Feed Extraction using user session cookies (Fastest & Native)
            fetchSubscriptionsFromHtmlFeed(cookieHeader, seenChannelIds, subscriptions)

            if (subscriptions.isNotEmpty()) {
                return@withContext Result.success(subscriptions)
            }

            // Engine 2: YouTube InnerTube API (FEchannels) with multi-token authorization
            var continuationToken: String? = null
            var pageCount = 0
            val maxPages = 50 // Guard against infinite pagination loops

            do {
                pageCount++
                val payloadJson = buildRequestBody(continuationToken)
                val authHeader = SapisidHashGenerator.generateFromCookieHeader(cookieHeader)
                    ?: SapisidHashGenerator.generate(sapisid)

                val endpointUrl = "$BROWSE_ENDPOINT?key=$INNERTUBE_API_KEY&prettyPrint=false"
                val request = Request.Builder()
                    .url(endpointUrl)
                    .post(payloadJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", CHROME_MOBILE_UA)
                    .header("Cookie", cookieHeader)
                    .header("Authorization", authHeader)
                    .header("Origin", "https://www.youtube.com")
                    .header("Referer", "https://www.youtube.com/feed/channels")
                    .header("X-Origin", "https://www.youtube.com")
                    .header("X-Goog-AuthUser", "0")
                    .header("X-Youtube-Client-Name", "1")
                    .header("X-Youtube-Client-Version", CLIENT_VERSION)
                    .build()

                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val responseText = response.body?.string().orEmpty()
                    response.close()

                    if (responseText.isNotBlank()) {
                        val jsonResponse = JSONObject(responseText)
                        val channels = parseChannelsFromJson(jsonResponse)
                        for (channel in channels) {
                            if (channel.channelId.isNotBlank() && seenChannelIds.add(channel.channelId)) {
                                subscriptions.add(channel)
                            }
                        }

                        continuationToken = extractContinuationToken(jsonResponse)
                        if (continuationToken != null && !seenTokens.add(continuationToken)) {
                            continuationToken = null
                        }
                    } else {
                        break
                    }
                } else {
                    response.close()
                    break
                }
            } while (continuationToken != null && pageCount < maxPages)

            // Engine 3: Fallback via FEsubscriptions browse endpoint
            if (subscriptions.isEmpty()) {
                fetchFallbackSubscriptions(cookieHeader, sapisid, seenChannelIds, subscriptions)
            }

            Result.success(subscriptions)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun fetchSubscriptionsFromHtmlFeed(
        cookieHeader: String,
        seenChannelIds: MutableSet<String>,
        subscriptions: MutableList<Subscription>
    ) {
        try {
            val req = Request.Builder()
                .url("https://www.youtube.com/feed/channels")
                .get()
                .header("User-Agent", CHROME_MOBILE_UA)
                .header("Cookie", cookieHeader)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
                .build()

            httpClient.newCall(req).execute().use { resp ->
                val html = resp.body?.string().orEmpty()
                if (resp.isSuccessful && html.isNotBlank()) {
                    val initialData = extractYtInitialDataFromHtml(html)
                    if (initialData != null) {
                        val channels = parseChannelsFromJson(initialData)
                        for (ch in channels) {
                            if (ch.channelId.isNotBlank() && seenChannelIds.add(ch.channelId)) {
                                subscriptions.add(ch)
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun fetchFallbackSubscriptions(
        cookieHeader: String,
        sapisid: String,
        seenChannelIds: MutableSet<String>,
        subscriptions: MutableList<Subscription>
    ) {
        val targets = listOf(
            "$BROWSE_ENDPOINT?key=$INNERTUBE_API_KEY&prettyPrint=false"
        )
        val auth = SapisidHashGenerator.generateFromCookieHeader(cookieHeader)
            ?: SapisidHashGenerator.generate(sapisid)
        for (targetUrl in targets) {
            val body = buildRequestBody(null, "FEsubscriptions").toString().toRequestBody(JSON_MEDIA_TYPE)
            val req = Request.Builder().url(targetUrl).post(body)
                .header("Content-Type", "application/json").header("User-Agent", CHROME_MOBILE_UA)
                .header("Cookie", cookieHeader).header("Authorization", auth)
                .header("Origin", "https://www.youtube.com").header("Referer", "https://www.youtube.com/feed/channels")
                .header("X-Origin", "https://www.youtube.com").header("X-Goog-AuthUser", "0")
                .header("X-Youtube-Client-Name", "1").header("X-Youtube-Client-Version", CLIENT_VERSION)
                .build()
            try {
                httpClient.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (resp.isSuccessful && text.isNotBlank()) {
                        for (ch in parseChannelsFromJson(JSONObject(text))) {
                            if (ch.channelId.isNotBlank() && seenChannelIds.add(ch.channelId)) subscriptions.add(ch)
                        }
                    }
                }
            } catch (_: Exception) {}
            if (subscriptions.isNotEmpty()) break
        }
    }

    private fun buildRequestBody(continuationToken: String?, browseId: String = "FEchannels"): JSONObject {
        val root = JSONObject()
        val context = JSONObject()
        val client = JSONObject()
        client.put("clientName", "WEB")
        client.put("clientVersion", CLIENT_VERSION)
        client.put("hl", "ar")
        client.put("gl", "EG")
        context.put("client", client)
        root.put("context", context)

        if (continuationToken != null) {
            root.put("continuation", continuationToken)
        } else {
            root.put("browseId", browseId)
        }

        return root
    }

    companion object {
        const val INNERTUBE_API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
        const val BROWSE_ENDPOINT = "https://www.youtube.com/youtubei/v1/browse"
        const val GUIDE_ENDPOINT = "https://www.youtube.com/youtubei/v1/guide"
        const val CLIENT_VERSION = "2.20261020.00.00"
        const val CHROME_MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.6478.122 Mobile Safari/537.36"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /**
         * YouTube default system/topic channels that should never be treated as user subscriptions.
         */
        val SYSTEM_CHANNEL_IDS = setOf(
            "UC-9-kyTW8ZkZNDHQJ6FgpwQ", // YouTube Music
            "UCOpNcN46UbXVtpKMrmU4Abg", // YouTube Gaming
            "UC4R8DWoMoI7CAwX8_LjQHig", // YouTube Live
            "UCEgdi0XIXXZ-qJOFPf4JSKw", // YouTube Sports
            "UCYfdidRxbB8Qhf0Nx7ioOYw", // YouTube News
            "UCokL_m68cIq42k5eAIdVnMQ", // Movies & TV
            "UCF0pVplsI8R5kcAqgtoRqoA"  // Popular on YouTube
        )

        fun defaultHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .build()
        }

        /**
         * Recursively searches for channel items across InnerTube JSON tree.
         */
        fun parseChannelsFromJson(root: JSONObject): List<Subscription> {
            val channels = mutableListOf<Subscription>()
            searchChannelsRecursive(root, channels)
            return channels
        }

        private fun searchChannelsRecursive(value: Any?, results: MutableList<Subscription>) {
            when (value) {
                is JSONObject -> {
                    val renderer = value.optJSONObject("subscriptionListItemRenderer")
                        ?: value.optJSONObject("channelRenderer")
                        ?: value.optJSONObject("gridChannelRenderer")
                        ?: value.optJSONObject("compactChannelRenderer")
                        ?: value.optJSONObject("guideEntryRenderer")

                    if (renderer != null) {
                        parseChannelRenderer(renderer)?.let { results.add(it) }
                    }

                    val keys = value.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        searchChannelsRecursive(value.opt(key), results)
                    }
                }
                is JSONArray -> {
                    for (i in 0 until value.length()) {
                        searchChannelsRecursive(value.opt(i), results)
                    }
                }
            }
        }

        private fun parseChannelRenderer(renderer: JSONObject): Subscription? {
            val browseEndpoint = renderer.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
            var channelId = renderer.optString("channelId").ifBlank {
                browseEndpoint?.optString("browseId").orEmpty().ifBlank {
                    browseEndpoint?.optString("canonicalBaseUrl")?.removePrefix("/").orEmpty()
                }
            }.ifBlank { renderer.optString("canonicalBaseUrl").removePrefix("/") }

            if (channelId.isBlank() || channelId.startsWith("FE") || channelId.startsWith("VL") || SYSTEM_CHANNEL_IDS.contains(channelId)) return null

            val title = extractText(renderer.opt("title")) ?: extractText(renderer.opt("formattedTitle")) ?: "قناة غير معروفة"
            val avatarUrl = extractThumbnailUrl(renderer.optJSONObject("thumbnail")) ?: extractThumbnailUrl(renderer.optJSONObject("thumbnails"))

            return Subscription(channelId = channelId, channelTitle = title, avatarUrl = avatarUrl)
        }

        private fun extractText(obj: Any?): String? {
            if (obj == null) return null
            if (obj is String) return obj
            if (obj is JSONObject) {
                val simpleText = obj.optString("simpleText")
                if (simpleText.isNotBlank()) return simpleText

                val runs = obj.optJSONArray("runs")
                if (runs != null && runs.length() > 0) {
                    val sb = StringBuilder()
                    for (i in 0 until runs.length()) {
                        val runObj = runs.optJSONObject(i)
                        val text = runObj?.optString("text").orEmpty()
                        sb.append(text)
                    }
                    if (sb.isNotBlank()) return sb.toString()
                }
            }
            return null
        }

        private fun extractThumbnailUrl(thumbObj: JSONObject?): String? {
            if (thumbObj == null) return null
            val thumbnails = thumbObj.optJSONArray("thumbnails")
            if (thumbnails != null && thumbnails.length() > 0) {
                val lastItem = thumbnails.optJSONObject(thumbnails.length() - 1)
                val url = lastItem?.optString("url")
                if (!url.isNullOrBlank()) {
                    return if (url.startsWith("//")) "https:$url" else url
                }
            }
            return null
        }

        /**
         * Recursively searches for next continuation token in InnerTube response.
         */
        fun extractContinuationToken(root: JSONObject): String? {
            return findContinuationRecursive(root)
        }

        private fun findContinuationRecursive(value: Any?): String? {
            when (value) {
                is JSONObject -> {
                    if (value.has("continuationCommand")) {
                        val token = value.optJSONObject("continuationCommand")?.optString("token")
                        if (!token.isNullOrBlank()) return token
                    }
                    if (value.has("nextContinuationData")) {
                        val token = value.optJSONObject("nextContinuationData")?.optString("continuation")
                        if (!token.isNullOrBlank()) return token
                    }

                    val keys = value.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val result = findContinuationRecursive(value.opt(key))
                        if (result != null) return result
                    }
                }
                is JSONArray -> {
                    for (i in 0 until value.length()) {
                        val result = findContinuationRecursive(value.opt(i))
                        if (result != null) return result
                    }
                }
            }
            return null
        }

        fun parseVideosFromJson(root: JSONObject): List<VideoFeedItem> {
            val videos = mutableListOf<VideoFeedItem>()
            val seenIds = mutableSetOf<String>()
            searchVideosRecursive(root, videos, seenIds)
            return videos
        }

        private fun searchVideosRecursive(value: Any?, results: MutableList<VideoFeedItem>, seenIds: MutableSet<String>) {
            when (value) {
                is JSONObject -> {
                    val renderer = value.optJSONObject("videoRenderer")
                        ?: value.optJSONObject("compactVideoRenderer")
                        ?: value.optJSONObject("gridVideoRenderer")

                    if (renderer != null) {
                        parseVideoRenderer(renderer)?.let { video ->
                            if (video.videoId.isNotBlank() && seenIds.add(video.videoId)) {
                                results.add(video)
                            }
                        }
                    }

                    val keys = value.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        searchVideosRecursive(value.opt(key), results, seenIds)
                    }
                }
                is JSONArray -> {
                    for (i in 0 until value.length()) {
                        searchVideosRecursive(value.opt(i), results, seenIds)
                    }
                }
            }
        }

        private fun parseVideoRenderer(renderer: JSONObject): VideoFeedItem? {
            val videoId = renderer.optString("videoId")
            if (videoId.isBlank()) return null

            val title = extractText(renderer.opt("title")) ?: "مقطع بدون عنوان"
            val uploaderName = extractText(renderer.opt("shortBylineText"))
                ?: extractText(renderer.opt("ownerText"))
                ?: extractText(renderer.opt("longBylineText"))
                ?: "يوتيوب"

            val browseEndpoint = renderer.optJSONObject("shortBylineText")
                ?.optJSONArray("runs")?.optJSONObject(0)
                ?.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
                ?: renderer.optJSONObject("ownerText")
                ?.optJSONArray("runs")?.optJSONObject(0)
                ?.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
                ?: renderer.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
            val uploaderUrl = browseEndpoint?.optString("browseId")?.ifBlank { null }
                ?: browseEndpoint?.optString("canonicalBaseUrl")?.ifBlank { null }

            val durationText = extractText(renderer.opt("lengthText"))
            val durationSeconds = parseDurationToSeconds(durationText)
            val uploadDate = extractText(renderer.opt("publishedTimeText"))
            val thumbnailUrl = extractThumbnailUrl(renderer.optJSONObject("thumbnail"))

            return VideoFeedItem(
                videoId = videoId,
                title = title,
                uploaderName = uploaderName,
                uploaderUrl = uploaderUrl,
                durationText = durationText,
                durationSeconds = durationSeconds,
                thumbnailUrl = thumbnailUrl,
                uploadDate = uploadDate
            )
        }

        private fun parseDurationToSeconds(durationText: String?): Long {
            if (durationText.isNullOrBlank()) return 0L
            val parts = durationText.trim().split(":")
            return when (parts.size) {
                1 -> parts[0].toLongOrNull() ?: 0L
                2 -> (parts[0].toLongOrNull() ?: 0L) * 60 + (parts[1].toLongOrNull() ?: 0L)
                3 -> (parts[0].toLongOrNull() ?: 0L) * 3600 + (parts[1].toLongOrNull() ?: 0L) * 60 + (parts[2].toLongOrNull() ?: 0L)
                else -> 0L
            }
        }

        /**
         * Robustly extracts embedded ytInitialData JSON from YouTube HTML SSR page.
         */
        fun extractYtInitialDataFromHtml(html: String): JSONObject? {
            val pattern = Pattern.compile("ytInitialData\\s*=\\s*(['\"{])")
            val matcher = pattern.matcher(html)
            if (!matcher.find()) return null

            val delimiter = matcher.group(1) ?: return null
            val startIdx = matcher.end() - 1

            return try {
                if (delimiter == "{") {
                    var depth = 0
                    var endIdx = -1
                    for (i in startIdx until html.length) {
                        val c = html[i]
                        if (c == '{') depth++
                        else if (c == '}') {
                            depth--
                            if (depth == 0) {
                                endIdx = i + 1
                                break
                            }
                        }
                    }
                    if (endIdx != -1) {
                        JSONObject(html.substring(startIdx, endIdx))
                    } else null
                } else {
                    val quoteChar = delimiter[0]
                    val endIdx = html.indexOf("$quoteChar;", startIdx + 1)
                    if (endIdx != -1) {
                        val rawStr = html.substring(startIdx + 1, endIdx)
                        val unescaped = unescapeHex(rawStr)
                        JSONObject(unescaped)
                    } else null
                }
            } catch (_: Exception) {
                null
            }
        }

        private fun unescapeHex(input: String): String {
            val sb = StringBuilder(input.length)
            var i = 0
            while (i < input.length) {
                if (input[i] == '\\' && i + 3 < input.length && input[i + 1] == 'x') {
                    try {
                        val hex = input.substring(i + 2, i + 4)
                        val code = hex.toInt(16)
                        sb.append(code.toChar())
                        i += 4
                        continue
                    } catch (_: Exception) {}
                }
                sb.append(input[i])
                i++
            }
            return sb.toString()
        }
    }
}
