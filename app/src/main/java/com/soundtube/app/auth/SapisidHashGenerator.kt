package com.soundtube.app.auth

import java.security.MessageDigest

/**
 * Computes YouTube dynamic SAPISIDHASH authorization tokens for InnerTube API requests.
 * Format: SAPISIDHASH {timestampSeconds}_{sha1Hex}
 * where sha1Hex is SHA-1 of "{timestampSeconds} {sapisid} {origin}"
 */
object SapisidHashGenerator {

    const val DEFAULT_ORIGIN = "https://www.youtube.com"

    /**
     * Generates the SAPISIDHASH authorization header value given a SAPISID cookie value.
     *
     * @param sapisid The raw SAPISID or __Secure-3PAPISID cookie value.
     * @param origin The YouTube origin URL, defaults to "https://www.youtube.com".
     * @param timestampSeconds Epoch timestamp in seconds. Defaults to current system time.
     * @return Complete Authorization header string e.g. "SAPISIDHASH 1697654400_abcdef..."
     */
    fun generate(
        sapisid: String,
        origin: String = DEFAULT_ORIGIN,
        timestampSeconds: Long = System.currentTimeMillis() / 1000
    ): String {
        require(sapisid.isNotBlank()) { "SAPISID cookie value cannot be blank" }

        val payload = "$timestampSeconds $sapisid $origin"
        val messageDigest = MessageDigest.getInstance("SHA-1")
        val digestBytes = messageDigest.digest(payload.toByteArray(Charsets.UTF_8))

        val sha1Hex = digestBytes.joinToString("") { byte ->
            "%02x".format(byte)
        }

        return "SAPISIDHASH ${timestampSeconds}_$sha1Hex"
    }

    /**
     * Helper to generate header directly from a full Cookie header string.
     */
    fun generateFromCookieHeader(
        cookieHeader: String,
        origin: String = DEFAULT_ORIGIN,
        timestampSeconds: Long = System.currentTimeMillis() / 1000
    ): String? {
        val sapisid = EncryptedAuthStorage.extractCookieValue(cookieHeader, "SAPISID")
            ?: EncryptedAuthStorage.extractCookieValue(cookieHeader, "__Secure-3PAPISID")
            ?: EncryptedAuthStorage.extractCookieValue(cookieHeader, "__Secure-1PAPISID")
            ?: return null

        return generate(sapisid, origin, timestampSeconds)
    }
}
