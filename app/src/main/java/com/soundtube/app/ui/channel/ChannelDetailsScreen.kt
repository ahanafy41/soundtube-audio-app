package com.soundtube.app.ui.channel

import android.widget.Toast
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
import com.soundtube.app.data.model.VideoFeedItem
import com.soundtube.app.data.model.VideoStreamOption
import com.soundtube.app.data.remote.NewPipeExtractorHelper
import com.soundtube.app.ui.common.AccessibleVideoCard
import com.soundtube.app.ui.common.VideoOptionsBottomSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    var videos by remember { mutableStateOf<List<VideoFeedItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var statusAnnouncement by remember { mutableStateOf("جارٍ تحميل مقاطع قناة $channelTitle...") }

    // Bottom sheet for video options
    var optionsItem by remember { mutableStateOf<VideoFeedItem?>(null) }
    var availableVideoStreams by remember { mutableStateOf<List<VideoStreamOption>>(emptyList()) }
    var isLoadingStreams by remember { mutableStateOf(false) }

    fun loadChannelVideos() {
        isLoading = true
        statusAnnouncement = "جارٍ تحميل مقاطع قناة $channelTitle..."
        coroutineScope.launch {
            val result = NewPipeExtractorHelper.getChannelFeed(channelId)
            isLoading = false
            result.onSuccess { list ->
                videos = list.sortedWith(compareByDescending { com.soundtube.app.data.model.parseUploadDateScore(it.uploadDate) })
                statusAnnouncement = "تم تحميل ${list.size} مقطع لقناة $channelTitle (الأحدث أولاً)."
            }.onFailure { err ->
                statusAnnouncement = "تعذر تحميل مقاطع القناة: ${err.localizedMessage}"
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
                        onClick = { loadChannelVideos() },
                        modifier = Modifier
                            .size(48.dp)
                            .semantics {
                                contentDescription = "إعادة تحميل مقاطع القناة"
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

            if (isLoading) {
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
                            onViewChannel = null // Already in channel screen
                        )
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
