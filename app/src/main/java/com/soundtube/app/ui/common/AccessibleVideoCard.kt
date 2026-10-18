package com.soundtube.app.ui.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.soundtube.app.data.model.VideoFeedItem
import com.soundtube.app.data.model.VideoStreamOption

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AccessibleVideoCard(
    item: VideoFeedItem,
    onPlay: (VideoFeedItem) -> Unit,
    onShowOptions: (VideoFeedItem) -> Unit,
    onViewChannel: ((VideoFeedItem) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val durationLabel = item.durationText ?: "المدة غير محددة"
    val a11ySummary = "مقطع: ${item.title}، الناشر: ${item.uploaderName}، المدة: $durationLabel. اضغط مرتين للتشغيل فوراً، أو اضغط مطولاً لخيارات التنزيل والقناة."

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp, horizontal = 8.dp)
            .combinedClickable(
                onClick = { onPlay(item) },
                onLongClick = { onShowOptions(item) }
            )
            .semantics(mergeDescendants = true) {
                contentDescription = a11ySummary
                customActions = listOfNotNull(
                    CustomAccessibilityAction(label = "تشغيل الصوت مباشرة") {
                        onPlay(item)
                        true
                    },
                    CustomAccessibilityAction(label = "فتح خيارات المقطع والتنزيل") {
                        onShowOptions(item)
                        true
                    },
                    onViewChannel?.let { action ->
                        CustomAccessibilityAction(label = "عرض صفحة القناة ${item.uploaderName}") {
                            action(item)
                            true
                        }
                    }
                )
            },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${item.uploaderName} • $durationLabel",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(
                onClick = { onShowOptions(item) },
                modifier = Modifier
                    .size(48.dp)
                    .semantics {
                        contentDescription = "خيارات مقطع ${item.title}"
                    }
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = null
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoOptionsBottomSheet(
    item: VideoFeedItem,
    isSubscribed: Boolean,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onDownloadAudio: () -> Unit,
    onDownloadVideo: (quality: String) -> Unit,
    onToggleSubscribe: () -> Unit,
    onViewChannel: (() -> Unit)? = null,
    availableVideoStreams: List<VideoStreamOption> = emptyList(),
    isLoadingStreams: Boolean = false
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.semantics {
            paneTitle = "خيارات المقطع: ${item.title}"
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Text(
                text = item.uploaderName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))

            // 1. Play directly
            AccessibleOptionItem(
                title = "تشغيل الصوت مباشرة",
                icon = Icons.Default.PlayArrow,
                onClick = {
                    onPlay()
                    onDismiss()
                }
            )

            // 2. Download Audio
            AccessibleOptionItem(
                title = "تنزيل صوتي (M4A)",
                icon = Icons.Default.Download,
                onClick = {
                    onDownloadAudio()
                    onDismiss()
                }
            )

            // 3. Download Video with resolutions
            if (isLoadingStreams) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = "جارٍ فحص جودات الفيديو المتاحة...",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else if (availableVideoStreams.isNotEmpty()) {
                availableVideoStreams.forEach { vStream ->
                    AccessibleOptionItem(
                        title = "تنزيل فيديو MP4 (${vStream.resolution})",
                        icon = Icons.Default.VideoLibrary,
                        onClick = {
                            onDownloadVideo(vStream.resolution)
                            onDismiss()
                        }
                    )
                }
            } else {
                AccessibleOptionItem(
                    title = "تنزيل فيديو MP4 (أفضل جودة متاحة)",
                    icon = Icons.Default.VideoLibrary,
                    onClick = {
                        onDownloadVideo("720p")
                        onDismiss()
                    }
                )
            }

            // 4. View Channel
            if (onViewChannel != null) {
                AccessibleOptionItem(
                    title = "عرض قناة ${item.uploaderName}",
                    icon = Icons.Default.AccountCircle,
                    onClick = {
                        onViewChannel()
                        onDismiss()
                    }
                )
            }

            // 5. Subscribe / Unsubscribe
            AccessibleOptionItem(
                title = if (isSubscribed) "إلغاء متابعة القناة" else "متابعة هذه القناة",
                icon = if (isSubscribed) Icons.Default.Check else Icons.Default.AddCircleOutline,
                onClick = {
                    onToggleSubscribe()
                    onDismiss()
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Close button
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .semantics {
                        contentDescription = "إغلاق نافذة الخيارات"
                    }
            ) {
                Icon(Icons.Default.Close, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("إغلاق")
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun AccessibleOptionItem(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .semantics {
                contentDescription = title
            },
        contentPadding = PaddingValues(horizontal = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
