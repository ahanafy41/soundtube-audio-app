package com.soundtube.app.ui.account

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.PersonOff
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Sync
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.soundtube.app.auth.EncryptedAuthStorage
import com.soundtube.app.auth.SyncSubscriptionsUseCase
import com.soundtube.app.auth.YouTubeInnerTubeClient
import com.soundtube.app.data.csv.TakeoutCsvExporter
import com.soundtube.app.data.csv.TakeoutCsvParser
import com.soundtube.app.data.local.SoundTubeDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSyncScreen(
    database: SoundTubeDatabase,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val authStorage = remember { EncryptedAuthStorage.getInstance(context) }
    val authState by authStorage.authState.collectAsState()

    val innerTubeClient = remember { YouTubeInnerTubeClient() }
    val syncUseCase = remember {
        SyncSubscriptionsUseCase(
            authStorage = authStorage,
            innerTubeClient = innerTubeClient,
            subscriptionDao = database.subscriptionDao()
        )
    }

    var isSyncing by remember { mutableStateOf(false) }
    var syncStatusAnnouncement by remember { mutableStateOf("جاهز للمزامنة") }
    var showLoginWebView by remember { mutableStateOf(false) }

    val updateManager = remember { com.soundtube.app.update.AppUpdateManager(context) }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<com.soundtube.app.update.UpdateInfo?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var isDownloadingUpdate by remember { mutableStateOf(false) }
    var updateProgressPercent by remember { mutableIntStateOf(0) }
    var updateDownloadedBytes by remember { mutableLongStateOf(0L) }
    var updateTotalBytes by remember { mutableLongStateOf(0L) }
    val currentVersionName = remember { updateManager.getCurrentVersionName() }

    // SAF Import CSV Launcher
    val importCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    context.contentResolver.openInputStream(uri)?.use { inputStream ->
                        val subscriptions = TakeoutCsvParser.parse(inputStream)
                        if (subscriptions.isNotEmpty()) {
                            database.subscriptionDao().insertAll(subscriptions)
                            withContext(Dispatchers.Main) {
                                syncStatusAnnouncement =
                                    "تم استيراد ${subscriptions.size} قناة بنجاح من ملف Takeout CSV"
                                Toast.makeText(context, syncStatusAnnouncement, Toast.LENGTH_LONG).show()
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                syncStatusAnnouncement =
                                    "لم يتم العثور على أي قنوات صالحة في الملف المحدد"
                                Toast.makeText(context, syncStatusAnnouncement, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        syncStatusAnnouncement = "فشل استيراد الملف: ${e.localizedMessage}"
                        Toast.makeText(context, syncStatusAnnouncement, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    // SAF Export CSV Launcher
    val exportCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val allSubs = database.subscriptionDao().getAllSubscriptionsList()
                    context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                        TakeoutCsvExporter.exportToOutputStream(allSubs, outputStream)
                    }
                    withContext(Dispatchers.Main) {
                        syncStatusAnnouncement =
                            "تم تصدير ${allSubs.size} قناة بنجاح إلى ملف CSV"
                        Toast.makeText(context, syncStatusAnnouncement, Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        syncStatusAnnouncement = "فشل تصدير الملف: ${e.localizedMessage}"
                        Toast.makeText(context, syncStatusAnnouncement, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    if (showLoginWebView) {
        YouTubeAuthWebView(
            authStorage = authStorage,
            onAuthSuccess = {
                showLoginWebView = false
                syncStatusAnnouncement = "تم تسجيل الدخول بنجاح! جارٍ مزامنة الاشتراكات..."
                isSyncing = true
                coroutineScope.launch {
                    val result = syncUseCase.execute()
                    isSyncing = false
                    result.fold(
                        onSuccess = { count ->
                            syncStatusAnnouncement = "تمت مزامنة $count قناة بنجاح من YouTube"
                            Toast.makeText(context, syncStatusAnnouncement, Toast.LENGTH_LONG).show()
                        },
                        onFailure = { error ->
                            syncStatusAnnouncement = "فشلت المزامنة: ${error.localizedMessage}"
                            Toast.makeText(context, syncStatusAnnouncement, Toast.LENGTH_LONG).show()
                        }
                    )
                }
            },
            onDismiss = {
                showLoginWebView = false
            }
        )
        return
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .semantics {
                paneTitle = "شاشة إدارة الحساب ومزامنة الاشتراكات"
            },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "إدارة الحساب والمزامنة",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.semantics {
                            contentDescription = "الرجوع إلى شاشة الاشتراكات السابقة"
                        }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Screen reader live region announcement
            Text(
                text = syncStatusAnnouncement,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = "حالة النظام: $syncStatusAnnouncement"
                    }
            )

            // Section 1: Account Status Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = if (authState.isLoggedIn) {
                            "بطاقة الحساب: مسجل الدخول باسم ${authState.userName}"
                        } else {
                            "بطاقة الحساب: غير مسجل الدخول، الوضع المحلي نشط"
                        }
                    },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = if (authState.isLoggedIn) Icons.Default.AccountCircle else Icons.Default.PersonOff,
                            contentDescription = null,
                            modifier = Modifier.size(36.dp),
                            tint = if (authState.isLoggedIn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                        )
                        Column {
                            Text(
                                text = if (authState.isLoggedIn) (authState.userName ?: "مستخدم YouTube") else "غير مسجل الدخول",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = if (authState.isLoggedIn) "جلسة YouTube نشطة" else "يمكنك المزامنة المباشرة بعد تسجيل الدخول",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    if (authState.isLoggedIn) {
                        OutlinedButton(
                            onClick = {
                                authStorage.clearAuth()
                                syncStatusAnnouncement = "تم تسجيل الخروج بنجاح"
                                Toast.makeText(context, syncStatusAnnouncement, Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .semantics {
                                    contentDescription = "تسجيل الخروج من حساب Google ومسح ملفات تعريف الارتباط المحفوظة"
                                }
                        ) {
                            Icon(Icons.Default.Logout, contentDescription = null)
                            Spacer(modifier = Modifier.size(8.dp))
                            Text("تسجيل الخروج")
                        }
                    } else {
                        Button(
                            onClick = {
                                showLoginWebView = true
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .semantics {
                                    contentDescription = "تسجيل الدخول بحساب Google لربط قنوات YouTube المشترك بها"
                                }
                        ) {
                            Icon(Icons.Default.Login, contentDescription = null)
                            Spacer(modifier = Modifier.size(8.dp))
                            Text("تسجيل الدخول بحساب Google")
                        }
                    }
                }
            }

            // Section 2: Online Subscriptions Synchronization
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = "قسم المزامنة المباشرة من خوادم YouTube عبر الإنترنت"
                    },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "المزامنة المباشرة عبر الإنترنت",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "جلب كافة القنوات المشترك بها من حسابك وحفظها في قاعدة البيانات المحلية للتطبيق.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    if (isSyncing) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                                .semantics {
                                    contentDescription = "جارٍ جلب الاشتراكات من YouTube، يرجى الانتظار"
                                }
                        )
                    }

                    Button(
                        onClick = {
                            if (!authState.isLoggedIn) {
                                syncStatusAnnouncement = "يرجى تسجيل الدخول أولاً للمزامنة"
                                Toast.makeText(context, syncStatusAnnouncement, Toast.LENGTH_SHORT).show()
                                return@Button
                            }

                            isSyncing = true
                            syncStatusAnnouncement = "بدأت مزامنة الاشتراكات من YouTube..."
                            coroutineScope.launch {
                                val result = syncUseCase.execute()
                                isSyncing = false
                                result.fold(
                                    onSuccess = { count ->
                                        syncStatusAnnouncement = "تمت مزامنة $count قناة بنجاح من YouTube"
                                        Toast.makeText(context, syncStatusAnnouncement, Toast.LENGTH_LONG).show()
                                    },
                                    onFailure = { error ->
                                        syncStatusAnnouncement = "فشلت المزامنة: ${error.localizedMessage}"
                                        Toast.makeText(context, syncStatusAnnouncement, Toast.LENGTH_LONG).show()
                                    }
                                )
                            }
                        },
                        enabled = authState.isLoggedIn && !isSyncing,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .semantics {
                                contentDescription = "مزامنة الاشتراكات الآن من خوادم YouTube"
                            }
                    ) {
                        Icon(Icons.Default.Sync, contentDescription = null)
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(if (isSyncing) "جارٍ المزامنة..." else "مزامنة الاشتراكات الآن")
                    }
                }
            }

            // Section 3: Offline Google Takeout CSV Import & Export
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = "قسم الاستيراد والتصدير دون إنترنت عبر ملفات Google Takeout CSV"
                    },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "الاستيراد والتصدير بدون إنترنت (Takeout CSV)",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "استيراد الاشتراكات مباشرة من ملف subscriptions.csv المستخرج من أداة Google Takeout، أو تصدير اشتراكاتك الحالية لملف CSV.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedButton(
                        onClick = {
                            importCsvLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "*/*"))
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .semantics {
                                contentDescription = "استيراد اشتراكات من ملف subscriptions.csv المخزن على الهاتف"
                            }
                    ) {
                        Icon(Icons.Default.FileOpen, contentDescription = null)
                        Spacer(modifier = Modifier.size(8.dp))
                        Text("استيراد ملف subscriptions.csv")
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = {
                            exportCsvLauncher.launch("soundtube_subscriptions.csv")
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .semantics {
                                contentDescription = "تصدير الاشتراكات المحلية الحالية وحفظها كملف CSV على الهاتف"
                            }
                    ) {
                        Icon(Icons.Default.SaveAlt, contentDescription = null)
                        Spacer(modifier = Modifier.size(8.dp))
                        Text("تصدير الاشتراكات إلى ملف CSV")
                    }
                }
            }

            // Section 4: In-App Updates Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = "قسم التحديثات، الإصدار الحالي $currentVersionName"
                    },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "تحديثات التطبيق",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "الإصدار المثبت حالياً: $currentVersionName",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "يمكنك التحقق من وجود إصدارات أحدث وتثبيتها تلقائياً ومباشرة من داخل التطبيق.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = {
                            isCheckingUpdate = true
                            syncStatusAnnouncement = "جارٍ التحقق من وجود تحديثات جديدة..."
                            coroutineScope.launch {
                                val result = updateManager.checkForUpdates()
                                isCheckingUpdate = false
                                result.fold(
                                    onSuccess = { info ->
                                        if (info.isUpdateAvailable) {
                                            updateInfo = info
                                            showUpdateDialog = true
                                            syncStatusAnnouncement = "يتوفر تحديث جديد: إصدار ${info.versionName}"
                                        } else {
                                            syncStatusAnnouncement = "أنت تستخدم أحدث إصدار متاح حالياً ($currentVersionName)"
                                            Toast.makeText(context, syncStatusAnnouncement, Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    onFailure = { error ->
                                        syncStatusAnnouncement = "تعذر التحقق من التحديثات: ${error.localizedMessage}"
                                        Toast.makeText(context, syncStatusAnnouncement, Toast.LENGTH_LONG).show()
                                    }
                                )
                            }
                        },
                        enabled = !isCheckingUpdate,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .semantics {
                                contentDescription = "التحقق من وجود تحديثات جديدة للتطبيق"
                            }
                    ) {
                        if (isCheckingUpdate) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.size(8.dp))
                            Text("جارٍ التحقق...")
                        } else {
                            Icon(Icons.Default.CloudDownload, contentDescription = null)
                            Spacer(modifier = Modifier.size(8.dp))
                            Text("التحقق من وجود تحديثات")
                        }
                    }
                }
            }
        }
    }

    if (showUpdateDialog && updateInfo != null) {
        val info = updateInfo!!
        AlertDialog(
            onDismissRequest = {
                if (!isDownloadingUpdate) showUpdateDialog = false
            },
            title = {
                Text("تحديث جديد متاح (${info.versionName})")
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (info.releaseNotes.isNotBlank()) {
                        Text("ما الجديد في هذا الإصدار:", style = MaterialTheme.typography.titleSmall)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(info.releaseNotes, style = MaterialTheme.typography.bodyMedium)
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                    if (isDownloadingUpdate) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                            if (updateProgressPercent >= 0) {
                                LinearProgressIndicator(
                                    progress = { updateProgressPercent / 100f },
                                    modifier = Modifier.fillMaxWidth().height(8.dp).semantics {
                                        contentDescription = "نسبة التقدم: $updateProgressPercent بالمئة"
                                    }
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                val mbDown = updateDownloadedBytes / (1024 * 1024f)
                                val mbTot = updateTotalBytes / (1024 * 1024f)
                                Text(
                                    text = "تم تنزيل: ${"%.1f".format(mbDown)} من ${"%.1f".format(mbTot)} ميجابايت ($updateProgressPercent%)",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            } else {
                                CircularProgressIndicator(modifier = Modifier.size(32.dp))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (!isDownloadingUpdate) {
                    Button(
                        onClick = {
                            val activity = context as? android.app.Activity
                            if (activity == null) {
                                Toast.makeText(context, "تعذر تحديد نافذة التثبيت", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            isDownloadingUpdate = true
                            syncStatusAnnouncement = "جارٍ تنزيل التحديث..."
                            coroutineScope.launch {
                                val dlRes = updateManager.downloadUpdate(info.apkUrl) { percent, down, total ->
                                    updateProgressPercent = percent
                                    updateDownloadedBytes = down
                                    updateTotalBytes = total
                                }
                                isDownloadingUpdate = false
                                dlRes.onSuccess { apkFile ->
                                    showUpdateDialog = false
                                    updateManager.installApk(activity, apkFile).onFailure { err ->
                                        Toast.makeText(context, "تنبيه التثبيت: ${err.localizedMessage}", Toast.LENGTH_LONG).show()
                                    }
                                }.onFailure { err ->
                                    Toast.makeText(context, "فشل التنزيل: ${err.localizedMessage}", Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        modifier = Modifier.height(48.dp).semantics { contentDescription = "تحديث وتثبيت الآن" }
                    ) {
                        Text("تحديث وتثبيت الآن")
                    }
                }
            },
            dismissButton = {
                if (!isDownloadingUpdate) {
                    OutlinedButton(
                        onClick = { showUpdateDialog = false },
                        modifier = Modifier.height(48.dp).semantics { contentDescription = "تأجيل، لاحقاً" }
                    ) {
                        Text("لاحقاً")
                    }
                }
            }
        )
    }
}
