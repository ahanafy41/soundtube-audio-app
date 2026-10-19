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
import com.soundtube.app.auth.EncryptedAuthStorage
import com.soundtube.app.auth.YouTubeInnerTubeClient
import com.soundtube.app.data.local.FeedCacheEntity
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

enum class HomeFeedTab {
    SUBSCRIPTIONS,
    RECOMMENDATIONS
}

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
    val cachedEntities by database.feedCacheDao().getCachedFeed().collectAsState(initial = emptyList())

    val authStorage = remember { EncryptedAuthStorage.getInstance(context) }
    val authState by authStorage.authState.collectAsState()
    val isGoogleLoggedIn = authState.isLoggedIn

    var currentFeedTab by remember { mutableStateOf(HomeFeedTab.SUBSCRIPTIONS) }

    var feedItems by remember { mutableStateOf<List<VideoFeedItem>>(emptyList()) }
    var recommendationsItems by remember { mutableStateOf<List<VideoFeedItem>>(emptyList()) }
    var isLoadingSubscriptions by remember { mutableStateOf(false) }
    var isLoadingRecommendations by remember { mutableStateOf(false) }
    var selectedChannelFilter by remember { mutableStateOf<String?>(null) }
    var statusAnnouncement by remember { mutableStateOf("الصفحة الرئيسية جاهزة") }

    // Bottom sheet state for video options
    var optionsItem by remember { mutableStateOf<VideoFeedItem?>(null) }
    var availableVideoStreams by remember { mutableStateOf<List<VideoStreamOption>>(emptyList()) }
    var isLoadingStreams by remember { mutableStateOf(false) }

    // REQ-020: Instant 0ms startup from local FeedCacheEntity
    LaunchedEffect(cachedEntities) {
        if (feedItems.isEmpty() && cachedEntities.isNotEmpty() && currentFeedTab == HomeFeedTab.SUBSCRIPTIONS) {
            feedItems = cachedEntities.map { entity ->
                VideoFeedItem(
                    videoId = entity.videoId,
                    title = entity.title,
                    uploaderName = entity.uploaderName,
                    uploaderUrl = entity.uploaderUrl,
                    durationText = entity.durationText,
                    durationSeconds = entity.durationSeconds,
                    thumbnailUrl = entity.thumbnailUrl,
                    uploadDate = entity.uploadDate
                )
            }
            statusAnnouncement = "تم عرض ${cachedEntities.size} مقطع من الذاكرة المؤقتة فوراً."
        }
    }

    fun refreshSubscriptionsFeed(subs: List<Subscription>) {
        if (subs.isEmpty()) {
            if (cachedEntities.isEmpty()) {
                feedItems = emptyList()
                statusAnnouncement = "لا توجد قنوات مشترك بها لعرض خلاصتها."
            }
            return
        }

        isLoadingSubscriptions = true
        if (feedItems.isEmpty()) {
            statusAnnouncement = "جارٍ تحميل أحدث المقاطع من القنوات المشترك بها..."
        }

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
                if (distinctResults.isNotEmpty()) {
                    feedItems = distinctResults
                    statusAnnouncement = "تم تحديث خلاصة الاشتراكات (${distinctResults.size} مقطع، الأحدث أولاً)."
                }
                isLoadingSubscriptions = false
            }

            // Persist to Room feed cache
            if (distinctResults.isNotEmpty()) {
                val cacheEntities = distinctResults.map { item ->
                    FeedCacheEntity(
                        videoId = item.videoId,
                        title = item.title,
                        uploaderName = item.uploaderName,
                        uploaderUrl = item.uploaderUrl,
                        durationText = item.durationText,
                        durationSeconds = item.durationSeconds,
                        thumbnailUrl = item.thumbnailUrl,
                        uploadDate = item.uploadDate
                    )
                }
                database.feedCacheDao().clear()
                database.feedCacheDao().insertAll(cacheEntities)
            }
        }
    }

    // REQ-024: Fast InnerTube recommendations fetch (~300ms)
    fun loadRecommendations() {
        val cookies = authStorage.getCookies()
        val sapisid = authStorage.getSapisid()
        if (cookies.isNullOrBlank() || sapisid.isNullOrBlank()) {
            statusAnnouncement = "يرجى تسجيل الدخول بحساب Google لعرض المقترحات."
            return
        }

        isLoadingRecommendations = true
        statusAnnouncement = "جارٍ جلب المقترحات المخصصة من حسابك في YouTube..."

        coroutineScope.launch {
            val result = YouTubeInnerTubeClient().fetchHomeFeed(cookies, sapisid)
            withContext(Dispatchers.Main) {
                isLoadingRecommendations = false
                result.onSuccess { recs ->
                    recommendationsItems = recs
                    statusAnnouncement = "تم تحميل ${recs.size} مقطع مقترح مخصص لحسابك."
                }.onFailure { err ->
                    statusAnnouncement = "تعذر تحميل المقترحات: ${err.localizedMessage}"
                }
            }
        }
    }

    LaunchedEffect(subscriptions) {
        if (subscriptions.isNotEmpty() && feedItems.isEmpty() && cachedEntities.isEmpty()) {
            refreshSubscriptionsFeed(subscriptions)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        // REQ-024: Feed Source Toggle (Subscriptions vs Recommendations for logged-in accounts)
        if (isGoogleLoggedIn) {
            TabRow(
                selectedTabIndex = if (currentFeedTab == HomeFeedTab.SUBSCRIPTIONS) 0 else 1,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        paneTitle = "نوع خلاصة الصفحة الرئيسية"
                    }
            ) {
                Tab(
                    selected = currentFeedTab == HomeFeedTab.SUBSCRIPTIONS,
                    onClick = { currentFeedTab = HomeFeedTab.SUBSCRIPTIONS },
                    text = { Text("اشتراكاتي") },
                    modifier = Modifier
                        .height(48.dp)
                        .semantics {
                            contentDescription = if (currentFeedTab == HomeFeedTab.SUBSCRIPTIONS) "تبويب اشتراكاتي، محدد حالياً" else "تبويب اشتراكاتي"
                        }
                )
                Tab(
                    selected = currentFeedTab == HomeFeedTab.RECOMMENDATIONS,
                    onClick = {
                        currentFeedTab = HomeFeedTab.RECOMMENDATIONS
                        if (recommendationsItems.isEmpty() && !isLoadingRecommendations) {
                            loadRecommendations()
                        }
                    },
                    text = { Text("مقترحات لك") },
                    modifier = Modifier
                        .height(48.dp)
                        .semantics {
                            contentDescription = if (currentFeedTab == HomeFeedTab.RECOMMENDATIONS) "تبويب مقترحات لك، محدد حالياً" else "تبويب مقترحات لك"
                        }
                )
            }
        }

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

        if (currentFeedTab == HomeFeedTab.RECOMMENDATIONS) {
            // Recommendations View
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "مقاطع مقترحة من يوتيوب بناءً على اهتماماتك",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = { loadRecommendations() },
                    modifier = Modifier
                        .size(48.dp)
                        .semantics {
                            contentDescription = "تحديث المقترحات المخصصة"
                        }
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                }
            }

            if (isLoadingRecommendations && recommendationsItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.semantics {
                            contentDescription = "جارٍ جلب المقترحات، يرجى الانتظار"
                        }
                    )
                }
            } else if (recommendationsItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "لا توجد مقترحات متوفرة حالياً.",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { loadRecommendations() },
                            modifier = Modifier
                                .height(48.dp)
                                .semantics {
                                    contentDescription = "إعادة محاولة جلب المقترحات"
                                }
                        ) {
                            Text("إعادة المحاولة")
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(recommendationsItems, key = { it.videoId }) { item ->
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
        } else {
            // Subscriptions View
            if (subscriptions.isEmpty() && feedItems.isEmpty()) {
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
                        onClick = { refreshSubscriptionsFeed(subscriptions) },
                        modifier = Modifier
                            .size(48.dp)
                            .semantics {
                                contentDescription = "تحديث خلاصة الصفحة الرئيسية"
                            }
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                    }
                }

                if (isLoadingSubscriptions && feedItems.isEmpty()) {
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

