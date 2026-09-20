package com.soundtube.app.data.remote

import com.soundtube.app.data.model.AudioStreamInfo
import com.soundtube.app.data.model.VideoFeedItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelExtractor
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.search.SearchExtractor
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeChannelLinkHandlerFactory
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamExtractor
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.IOException
import java.util.Collections
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody

object NewPipeExtractorHelper {

    @Volatile
    private var isInitialized = false

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun init() {
        if (isInitialized) return
        synchronized(this) {
            if (isInitialized) return
            NewPipe.init(object : Downloader() {
                override fun execute(request: Request): Response {
                    val httpMethod = request.httpMethod()
                    val url = request.url()
                    val headers = request.headers()
                    val dataToSend = request.dataToSend()

                    val reqBuilder = okhttp3.Request.Builder()
                        .url(url)

                    var hasUserAgent = false
                    headers.forEach { (name, values) ->
                        if (name.equals("User-Agent", ignoreCase = true)) hasUserAgent = true
                        values.forEach { v -> reqBuilder.addHeader(name, v) }
                    }
                    if (!hasUserAgent) {
                        reqBuilder.addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36")
                    }

                    when (httpMethod) {
                        "GET" -> reqBuilder.get()
                        "HEAD" -> reqBuilder.head()
                        "POST" -> {
                            val body = dataToSend?.toRequestBody() ?: ByteArray(0).toRequestBody()
                            reqBuilder.post(body)
                        }
                        else -> reqBuilder.method(httpMethod, null)
                    }

                    val call = okHttpClient.newCall(reqBuilder.build())
                    val response = call.execute()

                    val responseHeaders = mutableMapOf<String, List<String>>()
                    for (headerName in response.headers.names()) {
                        responseHeaders[headerName] = response.headers(headerName)
                    }

                    val responseBody = response.body?.string() ?: ""

                    return Response(
                        response.code,
                        response.message,
                        responseHeaders,
                        responseBody,
                        response.request.url.toString()
                    )
                }
            })
            isInitialized = true
        }
    }

    suspend fun getAudioStreamUrl(videoId: String): Result<AudioStreamInfo> = withContext(Dispatchers.IO) {
        try {
            init()
            val url = if (videoId.startsWith("http")) videoId else "https://www.youtube.com/watch?v=$videoId"
            val streamExtractor = ServiceList.YouTube.getStreamExtractor(url)
            streamExtractor.fetchPage()

            val audioStreams = streamExtractor.audioStreams
            if (audioStreams.isNullOrEmpty()) {
                return@withContext Result.failure(IOException("No audio streams found for video: $videoId"))
            }

            // Pick best bitrate stream (prefer m4a or opus)
            val sortedStreams = audioStreams.sortedByDescending { it.averageBitrate }
            val chosenStream: AudioStream = sortedStreams.firstOrNull { it.format?.name.equals("M4A", ignoreCase = true) }
                ?: sortedStreams.first()

            val info = AudioStreamInfo(
                videoId = streamExtractor.id ?: videoId,
                title = streamExtractor.name ?: "Unknown Title",
                channelTitle = streamExtractor.uploaderName ?: "Unknown Channel",
                channelId = streamExtractor.uploaderUrl ?: "",
                streamUrl = chosenStream.content ?: "",
                format = chosenStream.format?.name ?: "AUDIO",
                bitrate = chosenStream.averageBitrate,
                durationSeconds = streamExtractor.length,
                thumbnailUrl = streamExtractor.thumbnails?.firstOrNull()?.url
            )
            Result.success(info)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun searchAudio(query: String): Result<List<VideoFeedItem>> = withContext(Dispatchers.IO) {
        try {
            init()
            val searchExtractor = ServiceList.YouTube.getSearchExtractor(
                query,
                Collections.singletonList(YoutubeSearchQueryHandlerFactory.VIDEOS),
                ""
            )
            searchExtractor.fetchPage()

            val results = mutableListOf<VideoFeedItem>()
            val items = searchExtractor.initialPage?.items ?: emptyList()
            for (item in items) {
                if (item is StreamInfoItem) {
                    results.add(
                        VideoFeedItem(
                            videoId = item.url?.substringAfterLast("v=")?.substringBefore("&") ?: item.url ?: "",
                            title = item.name ?: "",
                            uploaderName = item.uploaderName ?: "",
                            uploaderUrl = item.uploaderUrl,
                            durationText = if (item.duration > 0) formatDuration(item.duration) else null,
                            durationSeconds = item.duration,
                            thumbnailUrl = item.thumbnails?.firstOrNull()?.url
                        )
                    )
                }
            }
            Result.success(results)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getChannelFeed(channelId: String): Result<List<VideoFeedItem>> = withContext(Dispatchers.IO) {
        try {
            init()
            val channelUrl = if (channelId.startsWith("http")) channelId else "https://www.youtube.com/channel/$channelId"
            val channelExtractor = ServiceList.YouTube.getChannelExtractor(channelUrl)
            channelExtractor.fetchPage()

            val results = mutableListOf<VideoFeedItem>()
            val linkHandler = ServiceList.YouTube.channelTabLHFactory
                .fromQuery(channelId, Collections.singletonList("videos"), "")
            val tabExtractor = ServiceList.YouTube.getChannelTabExtractor(linkHandler)
            tabExtractor.fetchPage()

            val items = tabExtractor.initialPage?.items ?: emptyList()
            for (item in items) {
                if (item is StreamInfoItem) {
                    results.add(
                        VideoFeedItem(
                            videoId = item.url?.substringAfterLast("v=")?.substringBefore("&") ?: item.url ?: "",
                            title = item.name ?: "",
                            uploaderName = item.uploaderName ?: channelExtractor.name ?: "",
                            uploaderUrl = channelUrl,
                            durationText = if (item.duration > 0) formatDuration(item.duration) else null,
                            durationSeconds = item.duration,
                            thumbnailUrl = item.thumbnails?.firstOrNull()?.url
                        )
                    )
                }
            }
            Result.success(results)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun formatDuration(seconds: Long): String {
        val minutes = seconds / 60
        val remainingSeconds = seconds % 60
        return "%d:%02d".format(minutes, remainingSeconds)
    }
}
