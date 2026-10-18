package com.soundtube.app.ui.home

import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    database: SoundTubeDatabase,
    onPlayVideo: (VideoFeedItem) -> Unit,
    onDownloadAudio: (VideoFeedItem) -> Unit,
    onDownloadVideo: (VideoFeedItem, quality: String) -> Unit,
    onSelectChannel: (channelId: String, channelTitle: String) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenAccountSync: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val subscriptions by database.subscriptionDao().getAllSubscriptions().collectAsState(initial = emptyList())

    var feedItems by remember { mutableStateOf<List<VideoFeedItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var selectedChannelFilter by remember { mutableStateOf<String?>(null) }
    var statusAnnouncement by remember { mutableStateOf("الصفحة الرئيسية جاهزة") }

    // Bottom sheet state for video options
    var optionsItem by remember { mutableStateOf<VideoFeedItem?>(null) }
    var availableVideoStreams by remember { mutableStateOf<List<VideoStreamOption>>(emptyList()) }
    var isLoadingStreams by remember { mutableStateOf(false) }

    fun refreshFeed(subs: List<Subscription>) {
        if (subs.isEmpty()) {
            feedItems = emptyList()
            isLoading = false
            statusAnnouncement = "لا توجد قنوات مشترك بها لعرض خلاصتها."
            return
        }

        isLoading = true
        statusAnnouncement = "جارٍ تحميل أحدث المقاطع من القنوات المشترك بها..."

        coroutineScope.launch {
            val targetSubs = subs.take(15) // Ingest top subscribed channels
            val deferredList = targetSubs.map { sub ->
                async {
                    val res = NewPipeExtractorHelper.getChannelFeed(sub.channelId)
                    res.getOrDefault(emptyList()).take(6)
                }
            }
            val results = deferredList.awaitAll().flatten()
            val distinctResults = results.distinctBy { it.videoId }
                .sortedWith(compareByDescending { com.soundtube.app.data.model.parseUploadDateScore(it.uploadDate) })

            withContext(Dispatchers.Main) {
                feedItems = distinctResults
                isLoading = false
                statusAnnouncement = "تم تحميل ${distinctResults.size} مقطع في الخلاصة الرئيسية (الأحدث أولاً)."
            }
        }
    }

    LaunchedEffect(subscriptions) {
        if (subscriptions.isNotEmpty() && feedItems.isEmpty()) {
            refreshFeed(subscriptions)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        // Accessibility Live Region
        Text(
            text = statusAnnouncement,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = statusAnnouncement
                }
        )

        if (subscriptions.isEmpty()) {
            // Empty Subscriptions Welcome Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Subscriptions,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "أهلاً بك في SoundTube",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "لم تقم بمتابعة أي قناة بعد. تابع قنواتك المفضلة أو قم باستيراد اشتراكاتك لتظهر لك أحدث المقاطع هنا مباشرة.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onOpenAccountSync,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .semantics {
                                    contentDescription = "استيراد ومزامنة الاشتراكات"
                                }
                        ) {
                            Icon(Icons.Default.Sync, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("استيراد الاشتراكات")
                        }

                        OutlinedButton(
                            onClick = onOpenSearch,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .semantics {
                                    contentDescription = "البحث عن مقاطع وقنوات في يوتيوب"
                                }
                        ) {
                            Icon(Icons.Default.Search, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("بحث في يوتيوب")
                        }
                    }
                }
            }
        } else {
            // Header with filters & Refresh button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = selectedChannelFilter == null,
                        onClick = { selectedChannelFilter = null },
                        label = { Text("الكل") },
                        modifier = Modifier.semantics {
                            contentDescription = if (selectedChannelFilter == null) "فلتر الكل، محدد حالياً" else "فلتر الكل"
                        }
                    )

                    subscriptions.forEach { sub ->
                        FilterChip(
                            selected = selectedChannelFilter == sub.channelTitle,
                            onClick = {
                                selectedChannelFilter = if (selectedChannelFilter == sub.channelTitle) null else sub.channelTitle
                            },
                            label = { Text(sub.channelTitle) },
                            modifier = Modifier.semantics {
                                contentDescription = if (selectedChannelFilter == sub.channelTitle) "فلتر قناة ${sub.channelTitle}، محدد حالياً" else "فلتر قناة ${sub.channelTitle}"
                            }
                        )
                    }
                }

                IconButton(
                    onClick = { refreshFeed(subscriptions) },
                    modifier = Modifier
                        .size(48.dp)
                        .semantics {
                            contentDescription = "تحديث خلاصة الصفحة الرئيسية"
                        }
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                }
            }

            if (isLoading && feedItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.semantics {
                            contentDescription = "جارٍ تحميل أحدث المقاطع من الاشتراكات..."
                        }
                    )
                }
            } else {
                val displayedList = if (selectedChannelFilter == null) {
                    feedItems
                } else {
                    feedItems.filter { it.uploaderName.contains(selectedChannelFilter!!, ignoreCase = true) }
                }

                if (displayedList.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "لا توجد مقاطع متاحة حالياً لهذا الفلتر.",
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 16.dp)
                    ) {
                        items(displayedList, key = { it.videoId }) { item ->
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
                                onViewChannel = {
                                    val channelId = item.uploaderUrl ?: item.uploaderName
                                    onSelectChannel(channelId, item.uploaderName)
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // Video Options Bottom Sheet
    optionsItem?.let { currentItem ->
        val isSubscribed = subscriptions.any {
            it.channelTitle.equals(currentItem.uploaderName, ignoreCase = true) ||
            it.channelId == currentItem.uploaderUrl
        }

        VideoOptionsBottomSheet(
            item = currentItem,
            isSubscribed = isSubscribed,
            availableVideoStreams = availableVideoStreams,
            isLoadingStreams = isLoadingStreams,
            onDismiss = { optionsItem = null },
            onPlay = { onPlayVideo(currentItem) },
            onDownloadAudio = { onDownloadAudio(currentItem) },
            onDownloadVideo = { quality -> onDownloadVideo(currentItem, quality) },
            onViewChannel = {
                val channelId = currentItem.uploaderUrl ?: currentItem.uploaderName
                onSelectChannel(channelId, currentItem.uploaderName)
            },
            onToggleSubscribe = {
                coroutineScope.launch {
                    val channelId = currentItem.uploaderUrl ?: currentItem.uploaderName
                    if (isSubscribed) {
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
