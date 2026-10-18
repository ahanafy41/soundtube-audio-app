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
                                    val currentUrl = request?.url?.toString().orEmpty()
                                    checkAndExtractCookies(currentUrl)
                                    return false
                                }

                                private fun checkAndExtractCookies(currentUrl: String?) {
                                    val targetUrl = currentUrl ?: "https://www.youtube.com"
                                    val cookies = cookieManager.getCookie("https://www.youtube.com")
                                        ?: cookieManager.getCookie(targetUrl)

                                    if (!cookies.isNullOrBlank()) {
                                        val hasSapisid = cookies.contains("SAPISID=") ||
                                                cookies.contains("__Secure-3PAPISID=")
                                        val hasLoginInfo = cookies.contains("LOGIN_INFO=")

                                        if (hasSapisid && (hasLoginInfo || targetUrl.contains("youtube.com"))) {
                                            authStorage.saveCookies(cookies)
                                            statusMessage = "تم تسجيل الدخول بنجاح! جارٍ حفظ الجلسة والمزامنة."
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
    "https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue"
