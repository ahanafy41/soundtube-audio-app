package com.soundtube.app.data.remote

import com.soundtube.app.data.model.AudioStreamInfo
import com.soundtube.app.data.model.MediaStreamDetails
import com.soundtube.app.data.model.PlaylistFeedItem
import com.soundtube.app.data.model.VideoFeedItem
import com.soundtube.app.data.model.VideoStreamOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelExtractor
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.search.SearchExtractor
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeChannelLinkHandlerFactory
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.VideoStream
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

    // In-memory stream URL cache (2 hours TTL) to make repeat audio playback instant
    private val streamCache = java.util.concurrent.ConcurrentHashMap<String, Pair<AudioStreamInfo, Long>>()
    private const val STREAM_CACHE_TTL_MS = 2 * 60 * 60 * 1000L

    fun clearStreamCache() {
        streamCache.clear()
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun init() {
        if (isInitialized) return
        synchronized(this) {
            if (isInitialized) return
            val downloader = object : Downloader() {
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
                    reqBuilder.header("Accept-Language", "ar, ar-EG;q=0.9, en;q=0.8")

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
            }
            NewPipe.init(downloader, Localization("ar", "EG"))
            isInitialized = true
        }
    }

    fun selectPreferredAudioStream(audioStreams: List<AudioStream>): AudioStream? {
        if (audioStreams.isEmpty()) return null

        // 1. First priority: Arabic tracks (audioLocale language == "ar")
        val arabicStreams = audioStreams.filter { stream ->
            stream.audioLocale?.language?.contains("ar", ignoreCase = true) == true
        }
        if (arabicStreams.isNotEmpty()) {
            val sorted = arabicStreams.sortedByDescending { it.averageBitrate }
            return sorted.firstOrNull { it.format?.name.equals("M4A", ignoreCase = true) } ?: sorted.first()
        }

        // 2. Second priority: Original audio tracks
        val originalStreams = audioStreams.filter { stream ->
            stream.audioTrackType?.name.equals("ORIGINAL", ignoreCase = true)
        }
        if (originalStreams.isNotEmpty()) {
            val sorted = originalStreams.sortedByDescending { it.averageBitrate }
            return sorted.firstOrNull { it.format?.name.equals("M4A", ignoreCase = true) } ?: sorted.first()
        }

        // 3. Fallback: streams that are NOT dubbed
        val nonDubbed = audioStreams.filter { stream ->
            !stream.audioTrackType?.name.equals("DUBBED", ignoreCase = true)
        }
        val candidateStreams = if (nonDubbed.isNotEmpty()) nonDubbed else audioStreams
        val sortedCandidates = candidateStreams.sortedByDescending { it.averageBitrate }
        return sortedCandidates.firstOrNull { it.format?.name.equals("M4A", ignoreCase = true) } ?: sortedCandidates.first()
    }

    suspend fun getAudioStreamUrl(videoId: String): Result<AudioStreamInfo> = withContext(Dispatchers.IO) {
        // Fast-path: check in-memory cache for instant 0ms playback
        val cached = streamCache[videoId]
        if (cached != null && (System.currentTimeMillis() - cached.second) < STREAM_CACHE_TTL_MS) {
            return@withContext Result.success(cached.first)
        }

        try {
            init()
            val url = if (videoId.startsWith("http")) videoId else "https://www.youtube.com/watch?v=$videoId"
            val streamExtractor = ServiceList.YouTube.getStreamExtractor(url)
            streamExtractor.fetchPage()

            val audioStreams = streamExtractor.audioStreams
            if (audioStreams.isNullOrEmpty()) {
                return@withContext Result.failure(IOException("No audio streams found for video: $videoId"))
            }

            // Prefer Arabic or Original audio track over random/highest bitrate
            val chosenStream: AudioStream = selectPreferredAudioStream(audioStreams)
                ?: return@withContext Result.failure(IOException("No suitable audio stream found for video: $videoId"))

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
            streamCache[videoId] = Pair(info, System.currentTimeMillis())
            Result.success(info)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getVideoStreams(videoId: String): Result<List<VideoStreamOption>> = withContext(Dispatchers.IO) {
        try {
            init()
            val url = if (videoId.startsWith("http")) videoId else "https://www.youtube.com/watch?v=$videoId"
            val streamExtractor = ServiceList.YouTube.getStreamExtractor(url)
            streamExtractor.fetchPage()

            val videoStreams = streamExtractor.videoStreams ?: emptyList()
            val options = videoStreams
                .filter { !it.content.isNullOrBlank() }
                .map { vStream ->
                    VideoStreamOption(
                        resolution = vStream.resolution ?: "360p",
                        format = vStream.format?.name ?: "MP4",
                        streamUrl = vStream.content ?: ""
                    )
                }
                .distinctBy { it.resolution }
                .sortedByDescending {
                    it.resolution.filter { char -> char.isDigit() }.toIntOrNull() ?: 0
                }

            Result.success(options)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getMediaStreamDetails(videoId: String): Result<MediaStreamDetails> = withContext(Dispatchers.IO) {
        try {
            init()
            val url = if (videoId.startsWith("http")) videoId else "https://www.youtube.com/watch?v=$videoId"
            val streamExtractor = ServiceList.YouTube.getStreamExtractor(url)
            streamExtractor.fetchPage()

            val audioStreams = streamExtractor.audioStreams ?: emptyList()
            val chosenAudio: AudioStream? = selectPreferredAudioStream(audioStreams)

            val audioInfo = chosenAudio?.let { stream ->
                AudioStreamInfo(
                    videoId = streamExtractor.id ?: videoId,
                    title = streamExtractor.name ?: "Unknown Title",
                    channelTitle = streamExtractor.uploaderName ?: "Unknown Channel",
                    channelId = streamExtractor.uploaderUrl ?: "",
                    streamUrl = stream.content ?: "",
                    format = stream.format?.name ?: "AUDIO",
                    bitrate = stream.averageBitrate,
                    durationSeconds = streamExtractor.length,
                    thumbnailUrl = streamExtractor.thumbnails?.firstOrNull()?.url
                )
            }

            val videoStreams = streamExtractor.videoStreams ?: emptyList()
            val videoOptions = videoStreams
                .filter { !it.content.isNullOrBlank() }
                .map { vStream ->
                    VideoStreamOption(
                        resolution = vStream.resolution ?: "360p",
                        format = vStream.format?.name ?: "MP4",
                        streamUrl = vStream.content ?: ""
                    )
                }
                .distinctBy { it.resolution }
                .sortedByDescending {
                    it.resolution.filter { char -> char.isDigit() }.toIntOrNull() ?: 0
                }

            Result.success(
                MediaStreamDetails(
                    videoId = streamExtractor.id ?: videoId,
                    title = streamExtractor.name ?: "Unknown Title",
                    channelTitle = streamExtractor.uploaderName ?: "Unknown Channel",
                    channelId = streamExtractor.uploaderUrl ?: "",
                    durationSeconds = streamExtractor.length,
                    thumbnailUrl = streamExtractor.thumbnails?.firstOrNull()?.url,
                    audioStream = audioInfo,
                    videoStreams = videoOptions
                )
            )
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
                    val uploadDate = item.textualUploadDate ?: item.uploadDate?.offsetDateTime()?.toString()
                    results.add(
                        VideoFeedItem(
                            videoId = item.url?.substringAfterLast("v=")?.substringBefore("&") ?: item.url ?: "",
                            title = item.name ?: "",
                            uploaderName = item.uploaderName ?: "",
                            uploaderUrl = item.uploaderUrl,
                            durationText = if (item.duration > 0) formatDuration(item.duration) else null,
                            durationSeconds = item.duration,
                            thumbnailUrl = item.thumbnails?.firstOrNull()?.url,
                            uploadDate = uploadDate
                        )
                    )
                }
            }
            Result.success(results)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getChannelFeedWithPage(
        channelId: String,
        page: Page? = null
    ): Result<Pair<List<VideoFeedItem>, Page?>> = withContext(Dispatchers.IO) {
        try {
            init()
            val channelUrl = if (channelId.startsWith("http")) channelId else "https://www.youtube.com/channel/$channelId"
            val linkHandler = ServiceList.YouTube.channelTabLHFactory
                .fromQuery(channelId, Collections.singletonList("videos"), "")
            val tabExtractor = ServiceList.YouTube.getChannelTabExtractor(linkHandler)

            val (items, nextPage) = if (page == null) {
                tabExtractor.fetchPage()
                Pair(tabExtractor.initialPage?.items ?: emptyList(), tabExtractor.initialPage?.nextPage)
            } else {
                val pageResult = tabExtractor.getPage(page)
                Pair(pageResult?.items ?: emptyList(), pageResult?.nextPage)
            }

            val results = mutableListOf<VideoFeedItem>()
            for (item in items) {
                if (item is StreamInfoItem) {
                    val uploadDate = item.textualUploadDate ?: item.uploadDate?.offsetDateTime()?.toString()
                    results.add(
                        VideoFeedItem(
                            videoId = item.url?.substringAfterLast("v=")?.substringBefore("&") ?: item.url ?: "",
                            title = item.name ?: "",
                            uploaderName = item.uploaderName ?: "",
                            uploaderUrl = channelUrl,
                            durationText = if (item.duration > 0) formatDuration(item.duration) else null,
                            durationSeconds = item.duration,
                            thumbnailUrl = item.thumbnails?.firstOrNull()?.url,
                            uploadDate = uploadDate
                        )
                    )
                }
            }
            Result.success(Pair(results, nextPage))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getChannelFeed(channelId: String): Result<List<VideoFeedItem>> {
        return getChannelFeedWithPage(channelId, null).map { it.first }
    }

    suspend fun getChannelPlaylists(channelId: String): Result<List<PlaylistFeedItem>> = withContext(Dispatchers.IO) {
        try {
            init()
            val linkHandler = ServiceList.YouTube.channelTabLHFactory
                .fromQuery(channelId, Collections.singletonList("playlists"), "")
            val tabExtractor = ServiceList.YouTube.getChannelTabExtractor(linkHandler)
            tabExtractor.fetchPage()

            val results = mutableListOf<PlaylistFeedItem>()
            val items = tabExtractor.initialPage?.items ?: emptyList()
            for (item in items) {
                if (item is PlaylistInfoItem) {
                    val playlistId = item.url?.substringAfter("list=")?.substringBefore("&") ?: item.url ?: ""
                    results.add(
                        PlaylistFeedItem(
                            playlistId = playlistId,
                            title = item.name ?: "قائمة تشغيل",
                            uploaderName = item.uploaderName ?: "",
                            videoCount = item.streamCount,
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
