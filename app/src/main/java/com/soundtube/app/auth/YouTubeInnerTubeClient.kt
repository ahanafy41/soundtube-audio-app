package com.soundtube.app.auth

import com.soundtube.app.data.local.Subscription
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

/**
 * YouTube InnerTube API client for fetching user subscriptions via FEchannels browse endpoint.
 * Authenticates using dynamic SAPISIDHASH signatures and session cookies.
 */
class YouTubeInnerTubeClient(
    private val httpClient: OkHttpClient = defaultHttpClient()
) {

    suspend fun fetchAllSubscriptions(
        cookieHeader: String,
        sapisid: String
    ): Result<List<Subscription>> = withContext(Dispatchers.IO) {
        try {
            val subscriptions = mutableListOf<Subscription>()
            val seenChannelIds = mutableSetOf<String>()
            val seenTokens = mutableSetOf<String>()

            var continuationToken: String? = null
            var pageCount = 0
            val maxPages = 50 // Guard against infinite pagination loops

            do {
                pageCount++
                val payloadJson = buildRequestBody(continuationToken)
                val authHeader = SapisidHashGenerator.generate(sapisid)

                val request = Request.Builder()
                    .url(BROWSE_ENDPOINT)
                    .post(payloadJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", CHROME_MOBILE_UA)
                    .header("Cookie", cookieHeader)
                    .header("Authorization", authHeader)
                    .header("X-Origin", "https://www.youtube.com")
                    .header("X-Youtube-Client-Name", "1")
                    .header("X-Youtube-Client-Version", CLIENT_VERSION)
                    .build()

                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    val code = response.code
                    val errorBody = response.body?.string().orEmpty()
                    response.close()
                    return@withContext Result.failure(
                        IOException("فشل الاتصال بـ YouTube InnerTube: كود $code - $errorBody")
                    )
                }

                val responseText = response.body?.string() ?: ""
                response.close()

                if (responseText.isBlank()) {
                    break
                }

                val jsonResponse = JSONObject(responseText)

                // Parse channels from current page
                val channels = parseChannelsFromJson(jsonResponse)
                for (channel in channels) {
                    if (channel.channelId.isNotBlank() && seenChannelIds.add(channel.channelId)) {
                        subscriptions.add(channel)
                    }
                }

                // Extract continuation token for the next page
                continuationToken = extractContinuationToken(jsonResponse)
                if (continuationToken != null && !seenTokens.add(continuationToken)) {
                    // Cyclic token detected
                    continuationToken = null
                }

            } while (continuationToken != null && pageCount < maxPages)

            Result.success(subscriptions)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun buildRequestBody(continuationToken: String?): JSONObject {
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
            root.put("browseId", "FEchannels")
        }

        return root
    }

    companion object {
        const val BROWSE_ENDPOINT = "https://www.youtube.com/youtubei/v1/browse"
        const val CLIENT_VERSION = "2.20240901.01.00"
        const val CHROME_MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.6478.122 Mobile Safari/537.36"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

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
                    if (value.has("channelRenderer")) {
                        val renderer = value.optJSONObject("channelRenderer")
                        if (renderer != null) {
                            parseChannelRenderer(renderer)?.let { results.add(it) }
                        }
                    } else if (value.has("gridChannelRenderer")) {
                        val renderer = value.optJSONObject("gridChannelRenderer")
                        if (renderer != null) {
                            parseChannelRenderer(renderer)?.let { results.add(it) }
                        }
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
            var channelId = renderer.optString("channelId")
            if (channelId.isBlank()) {
                channelId = renderer.optJSONObject("navigationEndpoint")
                    ?.optJSONObject("browseEndpoint")
                    ?.optString("browseId")
                    .orEmpty()
            }

            if (channelId.isBlank()) return null

            val title = extractText(renderer.opt("title")) ?: "قناة غير معروفة"
            val avatarUrl = extractThumbnailUrl(renderer.optJSONObject("thumbnail"))

            return Subscription(
                channelId = channelId,
                channelTitle = title,
                avatarUrl = avatarUrl
            )
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
    }
}
