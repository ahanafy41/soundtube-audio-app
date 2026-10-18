package com.soundtube.app

import android.content.ComponentName
import android.content.Context
import android.widget.Toast
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.soundtube.app.data.local.DownloadRecord
import com.soundtube.app.data.local.PlaybackHistory
import com.soundtube.app.data.local.SoundTubeDatabase
import com.soundtube.app.data.local.Subscription
import com.soundtube.app.data.model.AudioStreamInfo
import com.soundtube.app.data.model.VideoFeedItem
import com.soundtube.app.data.remote.NewPipeExtractorHelper
import com.soundtube.app.download.AudioDownloadManager
import com.soundtube.app.service.PlaybackService
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import com.soundtube.app.data.model.VideoStreamOption
import com.soundtube.app.ui.channel.ChannelDetailsScreen
import com.soundtube.app.ui.common.AccessibleVideoCard
import com.soundtube.app.ui.common.VideoOptionsBottomSheet
import com.soundtube.app.ui.home.HomeScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController by mutableStateOf<MediaController?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture?.addListener({
            mediaController = controllerFuture?.get()
        }, ContextCompat.getMainExecutor(this))

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF1E88E5),
                    secondary = Color(0xFF26A69A),
                    background = Color(0xFF121212),
                    surface = Color(0xFF1E1E1E),
                    onPrimary = Color.White,
                    onBackground = Color(0xFFE0E0E0),
                    onSurface = Color(0xFFE0E0E0)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainAppScaffold(
                        mediaController = mediaController,
                        onPlayAudio = { info ->
                            playStream(info)
                        },
                        onPlayLocalFile = { record ->
                            playLocalFile(record)
                        }
                    )
                }
            }
        }
    }

    private fun playStream(info: AudioStreamInfo) {
        val controller = mediaController ?: return
        val metadata = MediaMetadata.Builder()
            .setTitle(info.title)
            .setArtist(info.channelTitle)
            .build()

        val mediaItem = MediaItem.Builder()
            .setMediaId(info.videoId)
            .setUri(info.streamUrl)
            .setMediaMetadata(metadata)
            .build()

        // Check if there is a saved playback position
        val db = SoundTubeDatabase.getInstance(this)
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            val savedPos = db.playbackHistoryDao().getPlaybackPosition(info.videoId) ?: 0L
            withContext(Dispatchers.Main) {
                controller.setMediaItem(mediaItem)
                if (savedPos > 0) {
                    controller.seekTo(savedPos)
                }
                controller.prepare()
                controller.play()
            }
        }
    }

    private fun playLocalFile(record: DownloadRecord) {
        val controller = mediaController ?: return
        val metadata = MediaMetadata.Builder()
            .setTitle(record.title)
            .setArtist(record.channelTitle)
            .build()

        val mediaItem = MediaItem.Builder()
            .setMediaId(record.videoId)
            .setUri(record.localFilePath)
            .setMediaMetadata(metadata)
            .build()

        controller.setMediaItem(mediaItem)
        controller.prepare()
        controller.play()
    }

    override fun onDestroy() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onDestroy()
    }
}

fun getAppPermissions(): Array<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            android.Manifest.permission.POST_NOTIFICATIONS,
            android.Manifest.permission.READ_MEDIA_AUDIO,
            android.Manifest.permission.READ_MEDIA_VIDEO
        )
    } else {
        arrayOf(
            android.Manifest.permission.READ_EXTERNAL_STORAGE,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
    }
}

enum class SoundTubeTab(val titleAr: String) {
    HOME("الرئيسية"),
    SUBSCRIPTIONS("الاشتراكات"),
    DOWNLOADS("التنزيلات"),
    HISTORY("سجل الاستماع")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScaffold(
    mediaController: MediaController?,
    onPlayAudio: (AudioStreamInfo) -> Unit,
    onPlayLocalFile: (DownloadRecord) -> Unit
) {
    val context = LocalContext.current
    var currentTab by remember { mutableStateOf(SoundTubeTab.HOME) }
    var showSearchScreen by remember { mutableStateOf(false) }
    var showAccountSync by remember { mutableStateOf(false) }
    var selectedChannelForDetails by remember { mutableStateOf<Pair<String, String>?>(null) }

    val coroutineScope = rememberCoroutineScope()
    val database = remember { SoundTubeDatabase.getInstance(context) }
    val downloadManager = remember { AudioDownloadManager.getInstance(context) }

    // Request Runtime Permissions for Storage and Notifications
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissionsMap ->
        val allGranted = permissionsMap.values.all { it }
        if (!allGranted) {
            Toast.makeText(context, "أذونات التخزين مطلوبة لحفظ التنزيلات في مجلد SoundTube العام", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        val needed = getAppPermissions().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    // Unified media play & download handlers
    val playStreamFromItem: (VideoFeedItem) -> Unit = { item ->
        Toast.makeText(context, "جارٍ تجهيز وتشغيل: ${item.title}", Toast.LENGTH_SHORT).show()
        coroutineScope.launch {
            val result = NewPipeExtractorHelper.getAudioStreamUrl(item.videoId)
            result.onSuccess { info ->
                withContext(Dispatchers.Main) {
                    onPlayAudio(info)
                }
            }.onFailure { err ->
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "فشل استخراج الصوت: ${err.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    val downloadAudioFromItem: (VideoFeedItem) -> Unit = { item ->
        Toast.makeText(context, "جارٍ استخراج رابط الصوت: ${item.title}", Toast.LENGTH_SHORT).show()
        coroutineScope.launch {
            val result = NewPipeExtractorHelper.getAudioStreamUrl(item.videoId)
            result.onSuccess { info ->
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "بدأ تنزيل الصوت: ${info.title}", Toast.LENGTH_SHORT).show()
                }
                downloadManager.downloadAudio(
                    videoId = info.videoId,
                    title = info.title,
                    channelTitle = info.channelTitle,
                    streamUrl = info.streamUrl
                )
            }.onFailure { err ->
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "فشل التنزيل: ${err.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    val downloadVideoFromItem: (VideoFeedItem, String) -> Unit = { item, quality ->
        Toast.makeText(context, "جارٍ استخراج رابط الفيديو ($quality): ${item.title}", Toast.LENGTH_SHORT).show()
        coroutineScope.launch {
            val result = NewPipeExtractorHelper.getVideoStreams(item.videoId)
            result.onSuccess { streams ->
                val chosen = streams.firstOrNull { it.resolution.equals(quality, ignoreCase = true) }
                    ?: streams.firstOrNull()
                if (chosen != null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "بدأ تنزيل الفيديو (${chosen.resolution}): ${item.title}", Toast.LENGTH_SHORT).show()
                    }
                    downloadManager.downloadVideo(
                        videoId = item.videoId,
                        title = item.title,
                        channelTitle = item.uploaderName,
                        streamUrl = chosen.streamUrl,
                        resolution = chosen.resolution
                    )
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "لم يتوفر رابط فيديو بالجودة المطلوبة", Toast.LENGTH_SHORT).show()
                    }
                }
            }.onFailure { err ->
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "فشل استخراج الفيديو: ${err.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Live player state
    var isPlaying by remember { mutableStateOf(false) }
    var currentTitle by remember { mutableStateOf("") }
    var currentArtist by remember { mutableStateOf("") }
    var currentPositionMs by remember { mutableLongStateOf(0L) }
    var currentDurationMs by remember { mutableLongStateOf(0L) }

    DisposableEffect(mediaController) {
        val controller = mediaController ?: return@DisposableEffect onDispose {}
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                currentTitle = mediaMetadata.title?.toString() ?: ""
                currentArtist = mediaMetadata.artist?.toString() ?: ""
            }

            override fun onPlaybackStateChanged(state: Int) {
                currentPositionMs = controller.currentPosition
                currentDurationMs = controller.duration
            }
        }
        controller.addListener(listener)
        isPlaying = controller.isPlaying
        currentTitle = controller.mediaMetadata.title?.toString() ?: ""
        currentArtist = controller.mediaMetadata.artist?.toString() ?: ""
        currentPositionMs = controller.currentPosition
        currentDurationMs = controller.duration

        onDispose {
            controller.removeListener(listener)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val titleText = when {
                        showSearchScreen -> "بحث في YouTube"
                        selectedChannelForDetails != null -> selectedChannelForDetails!!.second
                        showAccountSync -> "إدارة الحساب والمزامنة"
                        else -> currentTab.titleAr
                    }
                    Text(text = titleText, maxLines = 1)
                },
                navigationIcon = {
                    if (showSearchScreen || selectedChannelForDetails != null || showAccountSync) {
                        IconButton(
                            onClick = {
                                if (showSearchScreen) showSearchScreen = false
                                else if (selectedChannelForDetails != null) selectedChannelForDetails = null
                                else if (showAccountSync) showAccountSync = false
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .semantics {
                                    contentDescription = "الرجوع للشاشة السابقة"
                                }
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = null
                            )
                        }
                    }
                },
                actions = {
                    if (!showSearchScreen) {
                        IconButton(
                            onClick = {
                                showSearchScreen = true
                                selectedChannelForDetails = null
                                showAccountSync = false
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .semantics {
                                    contentDescription = "فتح شاشة البحث في مقاطع يوتيوب"
                                }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null
                            )
                        }
                    }
                    IconButton(
                        onClick = {
                            showAccountSync = true
                            showSearchScreen = false
                            selectedChannelForDetails = null
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .semantics {
                                contentDescription = "فتح شاشة إدارة الحساب والمزامنة واستيراد الاشتراكات"
                            }
                    ) {
                        Icon(
                            imageVector = Icons.Default.ManageAccounts,
                            contentDescription = null
                        )
                    }
                }
            )
        },
        bottomBar = {
            Column {
                // Persistent Player Controls
                if (mediaController != null && currentTitle.isNotEmpty()) {
                    AccessibleMiniPlayer(
                        title = currentTitle,
                        artist = currentArtist,
                        isPlaying = isPlaying,
                        onTogglePlay = {
                            if (isPlaying) mediaController.pause() else mediaController.play()
                        },
                        onRewind = {
                            val target = (mediaController.currentPosition - 10000L).coerceAtLeast(0L)
                            mediaController.seekTo(target)
                        },
                        onForward = {
                            val target = mediaController.currentPosition + 30000L
                            mediaController.seekTo(target)
                        }
                    )
                }

                // Accessible Navigation Bar
                NavigationBar(
                    modifier = Modifier.semantics {
                        paneTitle = "شريط التبويبات الرئيسي"
                    }
                ) {
                    SoundTubeTab.values().forEach { tab ->
                        NavigationBarItem(
                            selected = currentTab == tab && !showAccountSync && !showSearchScreen && selectedChannelForDetails == null,
                            onClick = {
                                currentTab = tab
                                showSearchScreen = false
                                selectedChannelForDetails = null
                                showAccountSync = false
                            },
                            icon = {
                                when (tab) {
                                    SoundTubeTab.HOME -> Icon(Icons.Default.Home, contentDescription = null)
                                    SoundTubeTab.SUBSCRIPTIONS -> Icon(Icons.Default.Subscriptions, contentDescription = null)
                                    SoundTubeTab.DOWNLOADS -> Icon(Icons.Default.Download, contentDescription = null)
                                    SoundTubeTab.HISTORY -> Icon(Icons.Default.History, contentDescription = null)
                                }
                            },
                            label = { Text(tab.titleAr) },
                            modifier = Modifier.semantics {
                                contentDescription = if (currentTab == tab && !showAccountSync && !showSearchScreen && selectedChannelForDetails == null) {
                                    "تبويب ${tab.titleAr}، محدد حالياً"
                                } else {
                                    "تبويب ${tab.titleAr}"
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            if (showAccountSync) {
                com.soundtube.app.ui.account.AccountSyncScreen(
                    database = database,
                    onNavigateBack = { showAccountSync = false }
                )
            } else if (selectedChannelForDetails != null) {
                ChannelDetailsScreen(
                    channelId = selectedChannelForDetails!!.first,
                    channelTitle = selectedChannelForDetails!!.second,
                    database = database,
                    onNavigateBack = { selectedChannelForDetails = null },
                    onPlayVideo = { item -> playStreamFromItem(item) },
                    onDownloadAudio = { item -> downloadAudioFromItem(item) },
                    onDownloadVideo = { item, quality -> downloadVideoFromItem(item, quality) }
                )
            } else if (showSearchScreen) {
                SearchScreen(
                    database = database,
                    onPlayVideo = { item -> playStreamFromItem(item) },
                    onDownloadAudio = { item -> downloadAudioFromItem(item) },
                    onDownloadVideo = { item, quality -> downloadVideoFromItem(item, quality) },
                    onSelectChannel = { channelId, channelTitle ->
                        selectedChannelForDetails = Pair(channelId, channelTitle)
                        showSearchScreen = false
                    },
                    onNavigateBack = { showSearchScreen = false }
                )
            } else {
                when (currentTab) {
                    SoundTubeTab.HOME -> HomeScreen(
                        database = database,
                        onPlayVideo = { item -> playStreamFromItem(item) },
                        onDownloadAudio = { item -> downloadAudioFromItem(item) },
                        onDownloadVideo = { item, quality -> downloadVideoFromItem(item, quality) },
                        onSelectChannel = { channelId, channelTitle ->
                            selectedChannelForDetails = Pair(channelId, channelTitle)
                        },
                        onOpenSearch = { showSearchScreen = true },
                        onOpenAccountSync = { showAccountSync = true }
                    )

                    SoundTubeTab.SUBSCRIPTIONS -> SubscriptionsScreen(
                        database = database,
                        onSelectChannel = { sub ->
                            selectedChannelForDetails = Pair(sub.channelId, sub.channelTitle)
                        },
                        onOpenAccountSync = { showAccountSync = true }
                    )

                    SoundTubeTab.DOWNLOADS -> DownloadsScreen(
                        database = database,
                        downloadManager = downloadManager,
                        onPlayRecord = onPlayLocalFile
                    )

                    SoundTubeTab.HISTORY -> HistoryScreen(
                        database = database,
                        onPlayHistory = { historyItem ->
                            coroutineScope.launch {
                                val result = NewPipeExtractorHelper.getAudioStreamUrl(historyItem.videoId)
                                result.onSuccess { info ->
                                    onPlayAudio(info)
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun AccessibleMiniPlayer(
    title: String,
    artist: String,
    isPlaying: Boolean,
    onTogglePlay: () -> Unit,
    onRewind: () -> Unit,
    onForward: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
            .semantics {
                paneTitle = "مشغل الصوت الحالي"
            },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = "المقطع المشغل حالياً: $title، من القناة: $artist"
                    }
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1
                )
                Text(
                    text = artist,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onRewind,
                    modifier = Modifier.semantics {
                        contentDescription = "إرجاع عشر ثوانٍ للخلف"
                    }
                ) {
                    Icon(Icons.Default.Replay10, contentDescription = null)
                }

                IconButton(
                    onClick = onTogglePlay,
                    modifier = Modifier.semantics {
                        contentDescription = if (isPlaying) "إيقاف مؤقت للصوت" else "تشغيل الصوت"
                    }
                ) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = null
                    )
                }

                IconButton(
                    onClick = onForward,
                    modifier = Modifier.semantics {
                        contentDescription = "تقديم ثلاثين ثانية للأمام"
                    }
                ) {
                    Icon(Icons.Default.Forward30, contentDescription = null)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    database: SoundTubeDatabase,
    onPlayVideo: (VideoFeedItem) -> Unit,
    onDownloadAudio: (VideoFeedItem) -> Unit,
    onDownloadVideo: (VideoFeedItem, quality: String) -> Unit,
    onSelectChannel: ((channelId: String, channelTitle: String) -> Unit)? = null,
    onNavigateBack: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val subscriptions by database.subscriptionDao().getAllSubscriptions().collectAsState(initial = emptyList())

    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<VideoFeedItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var statusAnnouncement by remember { mutableStateOf("جاهز للبحث في YouTube") }

    var optionsItem by remember { mutableStateOf<VideoFeedItem?>(null) }
    var availableVideoStreams by remember { mutableStateOf<List<VideoStreamOption>>(emptyList()) }
    var isLoadingStreams by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("بحث عن مقطع صوتي أو بودكاست") },
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = "حقل كتابة للبحث عن المقاطع الصوتية والبودكاست"
                },
            singleLine = true,
            trailingIcon = {
                IconButton(
                    onClick = {
                        if (query.isNotBlank()) {
                            isLoading = true
                            statusAnnouncement = "جارٍ البحث عن $query"
                            coroutineScope.launch {
                                val searchRes = NewPipeExtractorHelper.searchAudio(query)
                                isLoading = false
                                searchRes.onSuccess {
                                    results = it
                                    statusAnnouncement = "تم العثور على ${it.size} نتيجة"
                                }.onFailure {
                                    statusAnnouncement = "حدث خطأ أثناء البحث: ${it.localizedMessage}"
                                }
                            }
                        }
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .semantics {
                            contentDescription = "زر تنفيذ البحث"
                        }
                ) {
                    Icon(Icons.Default.Search, contentDescription = null)
                }
            }
        )

        // Accessibility live region for status announcements
        Text(
            text = statusAnnouncement,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(vertical = 8.dp)
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = statusAnnouncement
                }
        )

        if (isLoading) {
            Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.semantics {
                        contentDescription = "جارٍ تحميل نتائج البحث، يرجى الانتظار"
                    }
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(results, key = { it.videoId }) { item ->
                    AccessibleVideoCard(
                        item = item,
                        onPlay = { onPlayVideo(item) },
                        onShowOptions = {
                            optionsItem = item
                            availableVideoStreams = emptyList()
                            isLoadingStreams = true
                            coroutineScope.launch {
                                val vResult = NewPipeExtractorHelper.getVideoStreams(item.videoId)
                                isLoadingStreams = false
                                vResult.onSuccess { streams ->
                                    availableVideoStreams = streams
                                }
                            }
                        },
                        onViewChannel = onSelectChannel?.let { handler ->
                            {
                                val channelId = item.uploaderUrl ?: item.uploaderName
                                handler(channelId, item.uploaderName)
                            }
                        }
                    )
                }
            }
        }
    }

    // Video Options Bottom Sheet
    optionsItem?.let { currentItem ->
        val isSub = subscriptions.any {
            it.channelTitle.equals(currentItem.uploaderName, ignoreCase = true) ||
            it.channelId == currentItem.uploaderUrl
        }

        VideoOptionsBottomSheet(
            item = currentItem,
            isSubscribed = isSub,
            availableVideoStreams = availableVideoStreams,
            isLoadingStreams = isLoadingStreams,
            onDismiss = { optionsItem = null },
            onPlay = { onPlayVideo(currentItem) },
            onDownloadAudio = { onDownloadAudio(currentItem) },
            onDownloadVideo = { quality -> onDownloadVideo(currentItem, quality) },
            onViewChannel = onSelectChannel?.let { handler ->
                {
                    val channelId = currentItem.uploaderUrl ?: currentItem.uploaderName
                    handler(channelId, currentItem.uploaderName)
                }
            },
            onToggleSubscribe = {
                coroutineScope.launch {
                    val channelId = currentItem.uploaderUrl ?: currentItem.uploaderName
                    if (isSub) {
                        database.subscriptionDao().unsubscribe(channelId)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "تم إلغاء متابعة ${currentItem.uploaderName}", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        val sub = Subscription(
                            channelId = channelId,
                            channelTitle = currentItem.uploaderName,
                            avatarUrl = null
                        )
                        database.subscriptionDao().subscribe(sub)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "تمت متابعة ${currentItem.uploaderName}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        )
    }
}

@Composable
fun SubscriptionsScreen(
    database: SoundTubeDatabase,
    onSelectChannel: (Subscription) -> Unit,
    onOpenAccountSync: () -> Unit
) {
    val subscriptions by database.subscriptionDao().getAllSubscriptions().collectAsState(initial = emptyList())
    val coroutineScope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "قنواتي المشترك بها",
                style = MaterialTheme.typography.titleLarge
            )
            IconButton(
                onClick = onOpenAccountSync,
                modifier = Modifier.semantics {
                    contentDescription = "فتح شاشة إدارة الحساب والمزامنة واستيراد ملفات Takeout"
                }
            ) {
                Icon(
                    imageVector = Icons.Default.ManageAccounts,
                    contentDescription = null
                )
            }
        }

        if (subscriptions.isEmpty()) {
            Column {
                Text(
                    text = "لا توجد اشتراكات حتى الآن. يمكنك متابعة القنوات من نتائج البحث أو مزامنتها مع حسابك.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics {
                        contentDescription = "لا توجد قنوات مشترك بها حالياً"
                    }
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(
                    onClick = onOpenAccountSync,
                    modifier = Modifier.semantics {
                        contentDescription = "فتح شاشة إدارة الحساب لتسجيل الدخول أو استيراد ملف Takeout CSV"
                    }
                ) {
                    Icon(Icons.Default.Sync, contentDescription = null)
                    Spacer(modifier = Modifier.size(8.dp))
                    Text("إدارة الحساب ومزامنة الاشتراكات")
                }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(subscriptions, key = { it.channelId }) { sub ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { onSelectChannel(sub) }
                            .semantics(mergeDescendants = true) {
                                contentDescription = "قناة ${sub.channelTitle}"
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = sub.channelTitle,
                                style = MaterialTheme.typography.titleMedium
                            )
                            IconButton(
                                onClick = {
                                    coroutineScope.launch {
                                        database.subscriptionDao().unsubscribe(sub.channelId)
                                    }
                                },
                                modifier = Modifier.semantics {
                                    contentDescription = "إلغاء الاشتراك من قناة ${sub.channelTitle}"
                                }
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DownloadsScreen(
    database: SoundTubeDatabase,
    downloadManager: AudioDownloadManager,
    onPlayRecord: (DownloadRecord) -> Unit
) {
    val downloads by database.downloadRecordDao().getAllDownloads().collectAsState(initial = emptyList())
    val liveStates by downloadManager.downloadStates.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = "التنزيلات المحفوظة (SoundTube)",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        // Show active downloading items
        liveStates.values.filter { it.state == com.soundtube.app.data.model.DownloadState.DOWNLOADING }.forEach { active ->
            val typeStr = if (active.isVideo) "فيديو" else "صوت"
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(text = "جارٍ تنزيل ($typeStr): ${active.title}")
                    LinearProgressIndicator(
                        progress = { active.progressPercent / 100f },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).semantics {
                            contentDescription = "نسبة تقدم تنزيل $typeStr: ${active.progressPercent} بالمائة"
                        }
                    )
                }
            }
        }

        if (downloads.isEmpty() && liveStates.isEmpty()) {
            Text(
                text = "لا توجد ملفات منزلة في مجلد /sdcard/Download/SoundTube/.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics {
                    contentDescription = "قائمة التنزيلات فارغة حالياً"
                }
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(downloads, key = { it.videoId }) { record ->
                    val isVideo = record.localFilePath.endsWith(".mp4", ignoreCase = true)
                    val typeLabel = if (isVideo) "فيديو" else "صوت"

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { onPlayRecord(record) }
                            .semantics(mergeDescendants = true) {
                                contentDescription = "$typeLabel: ${record.title}، القناة: ${record.channelTitle}. اضغط مرتين للتشغيل بدون إنترنت"
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = record.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 2
                                )
                                Text(
                                    text = "${record.channelTitle} • $typeLabel محفوظ في SoundTube",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(
                                onClick = {
                                    coroutineScope.launch {
                                        downloadManager.removeDownload(record.videoId)
                                    }
                                },
                                modifier = Modifier
                                    .size(48.dp)
                                    .semantics {
                                        contentDescription = "حذف ملف ${record.title}"
                                    }
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HistoryScreen(
    database: SoundTubeDatabase,
    onPlayHistory: (PlaybackHistory) -> Unit
) {
    val historyItems by database.playbackHistoryDao().getAllHistory().collectAsState(initial = emptyList())
    val coroutineScope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "سجل الاستماع واستئناف التشغيل",
                style = MaterialTheme.typography.titleLarge
            )
            if (historyItems.isNotEmpty()) {
                IconButton(
                    onClick = {
                        coroutineScope.launch {
                            database.playbackHistoryDao().clearHistory()
                        }
                    },
                    modifier = Modifier.semantics {
                        contentDescription = "مسح سجل الاستماع بالكامل"
                    }
                ) {
                    Icon(Icons.Default.ClearAll, contentDescription = null)
                }
            }
        }

        if (historyItems.isEmpty()) {
            Text(
                text = "سجل الاستماع فارغ حالياً.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics {
                    contentDescription = "سجل الاستماع فارغ حالياً"
                }
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(historyItems, key = { it.videoId }) { item ->
                    val posSec = item.positionMs / 1000
                    val posMin = posSec / 60
                    val posRemSec = posSec % 60
                    val posText = "%d:%02d".format(posMin, posRemSec)

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { onPlayHistory(item) }
                            .semantics(mergeDescendants = true) {
                                contentDescription = "استئناف ${item.title}، توقف عند الدقيقة ${posText}. اضغط مرتين للاستئناف"
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1
                                )
                                Text(
                                    text = "${item.channelTitle} • توقف عند $posText",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            IconButton(
                                onClick = {
                                    coroutineScope.launch {
                                        database.playbackHistoryDao().deleteHistory(item.videoId)
                                    }
                                },
                                modifier = Modifier.semantics {
                                    contentDescription = "حذف ${item.title} من السجل"
                                }
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null)
                            }
                        }
                    }
                }
            }
        }
    }
}
