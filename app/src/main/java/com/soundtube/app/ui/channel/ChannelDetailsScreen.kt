package com.soundtube.app.ui.channel

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.soundtube.app.data.local.SoundTubeDatabase
import com.soundtube.app.data.local.Subscription
import com.soundtube.app.data.model.PlaylistFeedItem
import com.soundtube.app.data.model.VideoFeedItem
import com.soundtube.app.data.model.VideoStreamOption
import com.soundtube.app.data.remote.NewPipeExtractorHelper
import com.soundtube.app.ui.common.AccessibleVideoCard
import com.soundtube.app.ui.common.VideoOptionsBottomSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.Page

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelDetailsScreen(
    channelId: String,
    channelTitle: String,
    database: SoundTubeDatabase,
    onNavigateBack: () -> Unit,
    onPlayVideo: (VideoFeedItem) -> Unit,
    onDownloadAudio: (VideoFeedItem) -> Unit,
    onDownloadVideo: (VideoFeedItem, quality: String) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val subscriptions by database.subscriptionDao().getAllSubscriptions().collectAsState(initial = emptyList())
    val isSubscribed = subscriptions.any { it.channelId == channelId || it.channelTitle.equals(channelTitle, ignoreCase = true) }

    var selectedTabIndex by remember { mutableIntStateOf(0) }

    // Videos state
    var videos by remember { mutableStateOf<List<VideoFeedItem>>(emptyList()) }
    var nextPage by remember { mutableStateOf<Page?>(null) }
    var isLoadingVideos by remember { mutableStateOf(true) }
    var isLoadingMore by remember { mutableStateOf(false) }

    // Playlists state
    var playlists by remember { mutableStateOf<List<PlaylistFeedItem>>(emptyList()) }
    var isLoadingPlaylists by remember { mutableStateOf(false) }

    var statusAnnouncement by remember { mutableStateOf("جارٍ تحميل مقاطع قناة $channelTitle...") }

    // Bottom sheet for video options
    var optionsItem by remember { mutableStateOf<VideoFeedItem?>(null) }
    var availableVideoStreams by remember { mutableStateOf<List<VideoStreamOption>>(emptyList()) }
    var isLoadingStreams by remember { mutableStateOf(false) }

    fun loadChannelVideos() {
        isLoadingVideos = true
        statusAnnouncement = "جارٍ تحميل مقاطع قناة $channelTitle..."
        coroutineScope.launch {
            val result = NewPipeExtractorHelper.getChannelFeedWithPage(channelId, null)
            isLoadingVideos = false
            result.onSuccess { (list, next) ->
                videos = list.sortedWith(compareByDescending { com.soundtube.app.data.model.parseUploadDateScore(it.uploadDate) })
                nextPage = next
                statusAnnouncement = "تم تحميل ${list.size} مقطع لقناة $channelTitle (الأحدث أولاً)."
            }.onFailure { err ->
                statusAnnouncement = "تعذر تحميل مقاطع القناة: ${err.localizedMessage}"
            }
        }
    }

    fun loadMoreVideos() {
        val page = nextPage ?: return
        if (isLoadingMore) return
        isLoadingMore = true
        statusAnnouncement = "جارٍ جلب المزيد من المقاطع..."
        coroutineScope.launch {
            val result = NewPipeExtractorHelper.getChannelFeedWithPage(channelId, page)
            isLoadingMore = false
            result.onSuccess { (newList, next) ->
                videos = (videos + newList).distinctBy { it.videoId }
                    .sortedWith(compareByDescending { com.soundtube.app.data.model.parseUploadDateScore(it.uploadDate) })
                nextPage = next
                statusAnnouncement = "تم تحميل ${newList.size} مقطع إضافي. الإجمالي الآن: ${videos.size} مقطع."
            }.onFailure { err ->
                statusAnnouncement = "فشل تحميل المقاطع الإضافية: ${err.localizedMessage}"
            }
        }
    }

    fun loadChannelPlaylists() {
        isLoadingPlaylists = true
        statusAnnouncement = "جارٍ جلب قوائم تشغيل قناة $channelTitle..."
        coroutineScope.launch {
            val result = NewPipeExtractorHelper.getChannelPlaylists(channelId)
            isLoadingPlaylists = false
            result.onSuccess { list ->
                playlists = list
                statusAnnouncement = "تم جلب ${list.size} قائمة تشغيل لقناة $channelTitle."
            }.onFailure { err ->
                statusAnnouncement = "تعذر جلب قوائم التشغيل: ${err.localizedMessage}"
            }
        }
    }

    LaunchedEffect(channelId) {
        loadChannelVideos()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = channelTitle,
                        maxLines = 1
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier
                            .size(48.dp)
                            .semantics {
                                contentDescription = "الرجوع من صفحة القناة إلى الشاشة السابقة"
                            }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            coroutineScope.launch {
                                if (isSubscribed) {
                                    database.subscriptionDao().unsubscribe(channelId)
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(context, "تم إلغاء متابعة $channelTitle", Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    val sub = Subscription(
                                        channelId = channelId,
                                        channelTitle = channelTitle,
                                        avatarUrl = null
                                    )
                                    database.subscriptionDao().subscribe(sub)
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(context, "تمت متابعة $channelTitle بنجاح", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .semantics {
                                contentDescription = if (isSubscribed) "إلغاء متابعة قناة $channelTitle" else "متابعة قناة $channelTitle"
                            }
                    ) {
                        Icon(
                            imageVector = if (isSubscribed) Icons.Default.Check else Icons.Default.AddCircleOutline,
                            contentDescription = null,
                            tint = if (isSubscribed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }

                    IconButton(
                        onClick = {
                            if (selectedTabIndex == 0) loadChannelVideos() else loadChannelPlaylists()
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .semantics {
                                contentDescription = if (selectedTabIndex == 0) "إعادة تحميل مقاطع القناة" else "إعادة تحميل قوائم التشغيل"
                            }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            // Accessible Channel Tab Bar
            TabRow(
                selectedTabIndex = selectedTabIndex,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        paneTitle = "تبويبات قناة $channelTitle"
                    }
            ) {
                Tab(
                    selected = selectedTabIndex == 0,
                    onClick = { selectedTabIndex = 0 },
                    text = { Text("الفيديوهات") },
                    modifier = Modifier
                        .height(48.dp)
                        .semantics {
                            contentDescription = if (selectedTabIndex == 0) "تبويب الفيديوهات، محدد حالياً" else "تبويب الفيديوهات"
                        }
                )
                Tab(
                    selected = selectedTabIndex == 1,
                    onClick = {
                        selectedTabIndex = 1
                        if (playlists.isEmpty() && !isLoadingPlaylists) {
                            loadChannelPlaylists()
                        }
                    },
                    text = { Text("قوائم التشغيل") },
                    modifier = Modifier
                        .height(48.dp)
                        .semantics {
                            contentDescription = if (selectedTabIndex == 1) "تبويب قوائم التشغيل، محدد حالياً" else "تبويب قوائم التشغيل"
                        }
                )
            }

            // Live Region Accessibility Announcement
            Text(
                text = statusAnnouncement,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = statusAnnouncement
                    }
            )

            if (selectedTabIndex == 0) {
                // Videos Tab
                if (isLoadingVideos) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.semantics {
                                contentDescription = "جارٍ تحميل مقاطع القناة، يرجى الانتظار"
                            }
                        )
                    }
                } else if (videos.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "لم يتم العثور على مقاطع لهذه القناة.",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = { loadChannelVideos() },
                                modifier = Modifier
                                    .height(48.dp)
                                    .semantics {
                                        contentDescription = "إعادة محاولة تحميل مقاطع القناة"
                                    }
                            ) {
                                Text("إعادة المحاولة")
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                    ) {
                        items(videos, key = { it.videoId }) { item ->
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
                                onViewChannel = null
                            )
                        }

                        if (nextPage != null) {
                            item {
                                Button(
                                    onClick = { loadMoreVideos() },
                                    enabled = !isLoadingMore,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 12.dp)
                                        .height(48.dp)
                                        .semantics {
                                            contentDescription = if (isLoadingMore) "جارٍ تحميل المزيد من المقاطع" else "تحميل المزيد من المقاطع"
                                        }
                                ) {
                                    if (isLoadingMore) {
                                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("جارٍ التحميل...")
                                    } else {
                                        Text("تحميل المزيد من المقاطع")
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // Playlists Tab
                if (isLoadingPlaylists) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.semantics {
                                contentDescription = "جارٍ تحميل قوائم تشغيل القناة، يرجى الانتظار"
                            }
                        )
                    }
                } else if (playlists.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "لا توجد قوائم تشغيل متاحة لهذه القناة.",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = { loadChannelPlaylists() },
                                modifier = Modifier
                                    .height(48.dp)
                                    .semantics {
                                        contentDescription = "إعادة محاولة تحميل قوائم التشغيل"
                                    }
                            ) {
                                Text("إعادة المحاولة")
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(playlists, key = { it.playlistId }) { playlist ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        Toast.makeText(context, "قائمة التشغيل: ${playlist.title}", Toast.LENGTH_SHORT).show()
                                    }
                                    .semantics {
                                        contentDescription = "قائمة تشغيل: ${playlist.title}، تتضمن ${playlist.videoCount} مقطع"
                                    },
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlaylistPlay,
                                        contentDescription = null,
                                        modifier = Modifier.size(36.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = playlist.title,
                                            style = MaterialTheme.typography.titleMedium,
                                            maxLines = 2
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "عدد المقاطع: ${playlist.videoCount} مقطع",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Video Options Bottom Sheet
    optionsItem?.let { currentItem ->
        VideoOptionsBottomSheet(
            item = currentItem,
            isSubscribed = isSubscribed,
            availableVideoStreams = availableVideoStreams,
            isLoadingStreams = isLoadingStreams,
            onDismiss = { optionsItem = null },
            onPlay = { onPlayVideo(currentItem) },
            onDownloadAudio = { onDownloadAudio(currentItem) },
            onDownloadVideo = { quality -> onDownloadVideo(currentItem, quality) },
            onViewChannel = null,
            onToggleSubscribe = {
                coroutineScope.launch {
                    if (isSubscribed) {
                        database.subscriptionDao().unsubscribe(channelId)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "تم إلغاء متابعة $channelTitle", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        val sub = Subscription(
                            channelId = channelId,
                            channelTitle = channelTitle,
                            avatarUrl = null
                        )
                        database.subscriptionDao().subscribe(sub)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "تمت متابعة $channelTitle", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        )
    }
}

