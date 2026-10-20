package com.soundtube.app.ui.account

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.soundtube.app.auth.EncryptedAuthStorage
import com.soundtube.app.auth.YouTubeInnerTubeClient

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubeAuthWebView(
    authStorage: EncryptedAuthStorage,
    onAuthSuccess: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var isLoading by remember { mutableStateOf(true) }
    var progress by remember { mutableFloatStateOf(0f) }
    var statusMessage by remember { mutableStateOf("جارٍ فتح صفحة تسجيل الدخول إلى Google") }
    var hasTriggeredSuccess by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .semantics {
                paneTitle = "شاشة تسجيل الدخول إلى Google و YouTube"
            },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "تسجيل الدخول إلى Google",
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.semantics {
                            contentDescription = "إغلاق نافذة تسجيل الدخول والرجوع إلى تطبيق SoundTube"
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
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
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Live region announcement for TalkBack / Jieshuo
            Text(
                text = statusMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = statusMessage
                    }
            )

            if (isLoading) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics {
                            contentDescription = "نسبة تحميل الصفحة: ${(progress * 100).toInt()} بالمائة"
                        }
                )
            }

            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { viewContext ->
                        WebView(viewContext).apply {
                            val cookieManager = CookieManager.getInstance()
                            cookieManager.setAcceptCookie(true)
                            cookieManager.setAcceptThirdPartyCookies(this, true)

                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                databaseEnabled = true
                                useWideViewPort = true
                                loadWithOverviewMode = true
                                // Set modern Chrome mobile user agent to bypass Google 403 error
                                userAgentString = YouTubeInnerTubeClient.CHROME_MOBILE_UA
                            }

                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    progress = newProgress / 100f
                                    isLoading = newProgress < 100
                                    if (newProgress == 100) {
                                        statusMessage = "اكتمل تحميل صفحة تسجيل الدخول"
                                    }
                                }
                            }

                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    isLoading = true
                                    statusMessage = "جارٍ تحميل الصفحة"
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    isLoading = false
                                    checkAndExtractCookies(url)
                                }

                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): Boolean {
                                    // Allow all redirects and page navigations naturally
                                    return false
                                }

                                private fun checkAndExtractCookies(currentUrl: String?) {
                                    val targetUrl = currentUrl ?: "https://www.youtube.com"

                                    // Guard against extracting during intermediate login handshakes
                                    if (targetUrl.contains("/signin") ||
                                        targetUrl.contains("ServiceLogin") ||
                                        targetUrl.contains("accounts.google.com") ||
                                        targetUrl.contains("CheckCookie")
                                    ) {
                                        return
                                    }

                                    if (hasTriggeredSuccess) return

                                    val isYouTubeDestination = targetUrl.contains("youtube.com") || targetUrl.contains("youtu.be")
                                    if (!isYouTubeDestination) {
                                        return
                                    }

                                    val ytCookies = cookieManager.getCookie("https://www.youtube.com").orEmpty()
                                    val mYtCookies = cookieManager.getCookie("https://m.youtube.com").orEmpty()
                                    val dotYtCookies = cookieManager.getCookie(".youtube.com").orEmpty()
                                    val googleCookies = cookieManager.getCookie("https://accounts.google.com").orEmpty()
                                    val targetCookies = cookieManager.getCookie(targetUrl).orEmpty()

                                    // Deduplicate and combine all session cookies
                                    val cookieMap = mutableMapOf<String, String>()
                                    fun addCookies(raw: String) {
                                        if (raw.isBlank()) return
                                        raw.split(";").forEach { part ->
                                            val trimmed = part.trim()
                                            val eqIdx = trimmed.indexOf('=')
                                            if (eqIdx > 0) {
                                                val key = trimmed.substring(0, eqIdx).trim()
                                                val value = trimmed.substring(eqIdx + 1).trim()
                                                if (key.isNotEmpty() && !cookieMap.containsKey(key)) {
                                                    cookieMap[key] = value
                                                }
                                            }
                                        }
                                    }
                                    addCookies(ytCookies)
                                    addCookies(mYtCookies)
                                    addCookies(dotYtCookies)
                                    addCookies(googleCookies)
                                    addCookies(targetCookies)

                                    val combinedCookies = cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }

                                    if (combinedCookies.isNotBlank()) {
                                        val hasSapisid = combinedCookies.contains("SAPISID=") ||
                                                combinedCookies.contains("__Secure-3PAPISID=") ||
                                                combinedCookies.contains("__Secure-1PAPISID=")
                                        val hasLoginInfo = combinedCookies.contains("LOGIN_INFO=")
                                        val hasSid = combinedCookies.contains("SID=") ||
                                                combinedCookies.contains("__Secure-3PSID=") ||
                                                combinedCookies.contains("__Secure-1PSID=")

                                        // Complete login requires valid SAPISID and YouTube session verification
                                        val isReady = hasSapisid && (hasLoginInfo || (hasSid && (targetUrl.contains("feed") || targetUrl.contains("channel") || targetUrl.contains("m.youtube.com"))))

                                        if (isReady && !hasTriggeredSuccess) {
                                            hasTriggeredSuccess = true
                                            cookieManager.flush()
                                            authStorage.saveCookies(combinedCookies)
                                            statusMessage = "تم تسجيل الدخول بنجاح! جارٍ حفظ الجلسة ومزامنة الاشتراكات."
                                            onAuthSuccess()
                                        }
                                    }
                                }
                            }

                            loadUrl(SIGN_IN_URL)
                        }
                    }
                )
            }
        }
    }
}

private const val SIGN_IN_URL =
    "https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26next%3Dhttps%253A%252F%252Fwww.youtube.com%252Ffeed%252Fchannels"
