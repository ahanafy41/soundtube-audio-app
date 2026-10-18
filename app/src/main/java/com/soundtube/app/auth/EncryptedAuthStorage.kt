package com.soundtube.app.auth

import android.content.Context
import android.content.SharedPreferences
import android.webkit.CookieManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.regex.Pattern

data class AuthState(
    val isLoggedIn: Boolean = false,
    val userName: String? = null,
    val userAvatarUrl: String? = null
)

/**
 * Manages encrypted storage for Google / YouTube authentication credentials.
 * Persists session cookies using AndroidX EncryptedSharedPreferences with hardware-backed MasterKey.
 */
class EncryptedAuthStorage private constructor(context: Context) {

    private val prefs: SharedPreferences

    private val _authState = MutableStateFlow(AuthState())
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    init {
        val appContext = context.applicationContext
        prefs = try {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                appContext,
                PREFS_FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Fallback for edge cases where Android KeyStore might throw on some custom ROMs
            appContext.getSharedPreferences(FALLBACK_PREFS_FILE_NAME, Context.MODE_PRIVATE)
        }

        refreshAuthState()
    }

    /**
     * Saves raw cookie header string extracted from YouTube WebView.
     */
    fun saveCookies(cookies: String) {
        prefs.edit().putString(KEY_COOKIES, cookies).apply()
        refreshAuthState()
    }

    /**
     * Retrieves stored cookie header string.
     */
    fun getCookies(): String? {
        return prefs.getString(KEY_COOKIES, null)
    }

    /**
     * Extracts SAPISID value from stored cookies.
     */
    fun getSapisid(): String? {
        val cookies = getCookies() ?: return null
        return extractCookieValue(cookies, "SAPISID")
            ?: extractCookieValue(cookies, "__Secure-3PAPISID")
            ?: extractCookieValue(cookies, "__Secure-1PAPISID")
    }

    /**
     * Updates optional profile details like username or channel avatar.
     */
    fun saveUserProfile(name: String?, avatarUrl: String?) {
        prefs.edit()
            .putString(KEY_USER_NAME, name)
            .putString(KEY_USER_AVATAR, avatarUrl)
            .apply()
        refreshAuthState()
    }

    /**
     * Checks if a valid session exists.
     */
    fun isLoggedIn(): Boolean {
        return !getSapisid().isNullOrBlank()
    }

    /**
     * Clears all session credentials, encrypted preferences, and WebView cookies.
     */
    fun clearAuth() {
        prefs.edit().clear().apply()

        // Clear WebKit cookies for YouTube & Google domains
        try {
            val cookieManager = CookieManager.getInstance()
            cookieManager.removeAllCookies(null)
            cookieManager.flush()
        } catch (_: Exception) {
            // Ignore cookie manager clearing issues on background threads
        }

        refreshAuthState()
    }

    private fun refreshAuthState() {
        val loggedIn = isLoggedIn()
        val name = prefs.getString(KEY_USER_NAME, null)
        val avatar = prefs.getString(KEY_USER_AVATAR, null)
        _authState.value = AuthState(
            isLoggedIn = loggedIn,
            userName = if (loggedIn) (name ?: "مستخدم YouTube") else null,
            userAvatarUrl = if (loggedIn) avatar else null
        )
    }

    companion object {
        private const val PREFS_FILE_NAME = "soundtube_secure_auth"
        private const val FALLBACK_PREFS_FILE_NAME = "soundtube_auth_fallback"
        private const val KEY_COOKIES = "auth_session_cookies"
        private const val KEY_USER_NAME = "auth_user_name"
        private const val KEY_USER_AVATAR = "auth_user_avatar"

        @Volatile
        private var instance: EncryptedAuthStorage? = null

        fun getInstance(context: Context): EncryptedAuthStorage {
            return instance ?: synchronized(this) {
                instance ?: EncryptedAuthStorage(context).also { instance = it }
            }
        }

        /**
         * Extracts a specific cookie value by key from a standard Cookie header string.
         */
        fun extractCookieValue(cookieHeader: String, key: String): String? {
            val pattern = Pattern.compile("(?:^|;\\s*)${Pattern.quote(key)}=([^;]+)")
            val matcher = pattern.matcher(cookieHeader)
            return if (matcher.find()) {
                matcher.group(1)?.trim()
            } else {
                null
            }
        }
    }
}
