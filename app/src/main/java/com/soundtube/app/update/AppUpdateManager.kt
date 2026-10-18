package com.soundtube.app.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val releaseNotes: String,
    val apkUrl: String,
    val isUpdateAvailable: Boolean
)

class AppUpdateManager(private val context: Context) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val PRIMARY_VERSION_URL =
            "https://raw.githubusercontent.com/ahanafy41/soundtube-audio-app/main/version.json"
        private const val FALLBACK_RELEASE_URL =
            "https://api.github.com/repos/ahanafy41/soundtube-audio-app/releases/latest"
        private const val MAX_APK_SIZE = 120L * 1024 * 1024 // 120 MB
    }

    fun getCurrentVersionCode(): Long {
        val pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            pInfo.versionCode.toLong()
        }
    }

    fun getCurrentVersionName(): String {
        val pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        return pInfo.versionName ?: "1.0.0"
    }

    suspend fun checkForUpdates(): Result<UpdateInfo> = withContext(Dispatchers.IO) {
        try {
            // 1. Try Primary version.json
            val primaryRequest = Request.Builder()
                .url(PRIMARY_VERSION_URL)
                .header("Accept", "application/json")
                .header("Cache-Control", "no-cache")
                .build()

            val primaryResult = runCatching {
                httpClient.newCall(primaryRequest).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val bodyString = response.body?.string() ?: throw IOException("Empty body")
                    val json = JSONObject(bodyString)
                    val remoteVersionCode = json.optLong("versionCode", 0L)
                    val remoteVersionName = json.optString("versionName", "")
                    val releaseNotes = json.optString("releaseNotes", "")
                    val apkUrl = json.optString("apkUrl", "")
                    val currentVersionCode = getCurrentVersionCode()

                    UpdateInfo(
                        versionCode = remoteVersionCode,
                        versionName = remoteVersionName,
                        releaseNotes = releaseNotes,
                        apkUrl = apkUrl,
                        isUpdateAvailable = remoteVersionCode > currentVersionCode
                    )
                }
            }

            if (primaryResult.isSuccess) {
                return@withContext primaryResult
            }

            // 2. Fallback to GitHub Release API
            val fallbackRequest = Request.Builder()
                .url(FALLBACK_RELEASE_URL)
                .header("Accept", "application/vnd.github+json")
                .header("Cache-Control", "no-cache")
                .build()

            httpClient.newCall(fallbackRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("فشل التحقق من التحديثات: رمز الاستجابة ${response.code}")
                }
                val bodyString = response.body?.string() ?: throw IOException("استجابة خادم التحديثات فارغة")
                val json = JSONObject(bodyString)
                val tagName = json.optString("tag_name", "").removePrefix("v")
                val releaseNotes = json.optString("body", "")

                var apkDownloadUrl = ""
                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name", "")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            apkDownloadUrl = asset.optString("browser_download_url", "")
                            break
                        }
                    }
                }

                val currentVersionName = getCurrentVersionName()
                val isNewer = isVersionStringNewer(tagName, currentVersionName)
                val currentVersionCode = getCurrentVersionCode()
                // Use incremented code if newer tag found
                val remoteVersionCode = if (isNewer) currentVersionCode + 1 else currentVersionCode

                Result.success(
                    UpdateInfo(
                        versionCode = remoteVersionCode,
                        versionName = tagName,
                        releaseNotes = releaseNotes,
                        apkUrl = apkDownloadUrl,
                        isUpdateAvailable = isNewer
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun isVersionStringNewer(remoteVersion: String, currentVersion: String): Boolean {
        if (remoteVersion.isBlank() || currentVersion.isBlank()) return false
        val rParts = remoteVersion.split('.').mapNotNull { it.toIntOrNull() }
        val cParts = currentVersion.split('.').mapNotNull { it.toIntOrNull() }
        val maxLen = maxOf(rParts.size, cParts.size)
        for (i in 0 until maxLen) {
            val r = rParts.getOrElse(i) { 0 }
            val c = cParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }

    suspend fun downloadUpdate(
        apkUrl: String,
        onProgress: (percent: Int, downloadedBytes: Long, totalBytes: Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val updatesDir = File(context.cacheDir, "updates")
            if (!updatesDir.exists()) {
                updatesDir.mkdirs()
            }

            val targetFile = File(updatesDir, "soundtube-update.apk")
            val stagingFile = File(updatesDir, "soundtube-update.apk.tmp")

            if (stagingFile.exists()) {
                stagingFile.delete()
            }

            val request = Request.Builder()
                .url(apkUrl)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("فشل تنزيل ملف التحديث: HTTP ${response.code}")
                }
                val body = response.body ?: throw IOException("محتوى حزمة التحديث فارغ")
                val totalBytes = body.contentLength()
                if (totalBytes > MAX_APK_SIZE) {
                    throw IOException("حجم حزمة التحديث يتجاوز الحد المسموح به")
                }

                body.byteStream().use { input ->
                    FileOutputStream(stagingFile).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var downloadedBytes = 0L
                        var lastProgressPercent = -1

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead
                            val percent = if (totalBytes > 0) {
                                ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
                            } else {
                                -1
                            }
                            if (percent != lastProgressPercent) {
                                lastProgressPercent = percent
                                onProgress(percent, downloadedBytes, totalBytes)
                            }
                        }
                        output.fd.sync()
                    }
                }
            }

            // Replace target file atomically
            if (targetFile.exists()) {
                targetFile.delete()
            }
            if (!stagingFile.renameTo(targetFile)) {
                throw IOException("فشل نقل ملف التحديث إلى مساره النهائي")
            }

            // Verify integrity
            val validation = validateApk(targetFile)
            if (validation.isFailure) {
                targetFile.delete()
                return@withContext Result.failure(
                    validation.exceptionOrNull() ?: IllegalStateException("فشل التحقق من سلامة الحزمة")
                )
            }

            Result.success(targetFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun validateApk(apkFile: File): Result<Unit> {
        if (!apkFile.exists() || apkFile.length() == 0L) {
            return Result.failure(IllegalStateException("ملف التحديث غير موجود أو فارغ"))
        }

        val pm = context.packageManager
        val archiveInfo = pm.getPackageArchiveInfo(apkFile.absolutePath, 0)
            ?: return Result.failure(IllegalStateException("لا يمكن قراءة حزمة التحديث (ملف APK تالف)"))

        if (archiveInfo.packageName != context.packageName) {
            return Result.failure(
                IllegalStateException("اسم حزمة التحديث (${archiveInfo.packageName}) لا يطابق التطبيق (${context.packageName})")
            )
        }

        val archiveVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archiveInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            archiveInfo.versionCode.toLong()
        }

        val currentVersionCode = getCurrentVersionCode()
        if (archiveVersionCode < currentVersionCode) {
            return Result.failure(
                IllegalStateException("إصدار الحزمة المنزلة ($archiveVersionCode) أقل من الإصدار الحالي ($currentVersionCode)")
            )
        }

        return Result.success(Unit)
    }

    fun canRequestPackageInstalls(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun openUnknownAppSourcesSettings(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            activity.startActivity(intent)
        }
    }

    fun installApk(activity: Activity, apkFile: File): Result<Unit> {
        if (!canRequestPackageInstalls()) {
            openUnknownAppSourcesSettings(activity)
            return Result.failure(SecurityException("يتطلب التثبيت السماح بتثبيت التطبيقات غير المعروفة"))
        }

        val validation = validateApk(apkFile)
        if (validation.isFailure) return validation

        return try {
            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, apkFile)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
