package com.soundtube.app.auth

import java.security.MessageDigest

/**
 * Computes YouTube dynamic SAPISIDHASH authorization tokens for InnerTube API requests.
 * Format: SAPISIDHASH {timestampSeconds}_{sha1Hex}
 * where sha1Hex is SHA-1 of "{timestampSeconds} {sapisid} {origin}"
 * Also generates SAPISID3PHASH and SAPISID1PHASH for partitioned and secure cookies.
 */
object SapisidHashGenerator {

    const val DEFAULT_ORIGIN = "https://www.youtube.com"

    /**
     * Helper to compute lowercase hexadecimal SHA-1 string.
     */
    fun computeSha1(payload: String): String {
        val messageDigest = MessageDigest.getInstance("SHA-1")
        val digestBytes = messageDigest.digest(payload.toByteArray(Charsets.UTF_8))
        return digestBytes.joinToString("") { byte ->
            "%02x".format(byte)
        }
    }

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
        val sha1Hex = computeSha1("$timestampSeconds $sapisid $origin")
        return "SAPISIDHASH ${timestampSeconds}_${sha1Hex}_u"
    }

    /**
     * Helper to generate header directly from a full Cookie header string.
     * Produces all compatible tokens (SAPISIDHASH, SAPISID3PHASH, SAPISID1PHASH)
     * so that YouTube's InnerTube authentication succeeds across standard, partitioned,
     * and secure browser cookie scopes.
     */
    fun generateFromCookieHeader(
        cookieHeader: String,
        origin: String = DEFAULT_ORIGIN,
        timestampSeconds: Long = System.currentTimeMillis() / 1000
    ): String? {
        val sapisid = EncryptedAuthStorage.extractCookieValue(cookieHeader, "SAPISID")
        val sapisid3p = EncryptedAuthStorage.extractCookieValue(cookieHeader, "__Secure-3PAPISID")
        val sapisid1p = EncryptedAuthStorage.extractCookieValue(cookieHeader, "__Secure-1PAPISID")

        if (sapisid == null && sapisid3p == null && sapisid1p == null) {
            return null
        }

        val tokens = mutableListOf<String>()

        // 1. Primary standard SAPISID token
        val primaryKey = sapisid ?: sapisid3p ?: sapisid1p
        if (primaryKey != null) {
            val hash = computeSha1("$timestampSeconds $primaryKey $origin")
            tokens.add("SAPISIDHASH ${timestampSeconds}_${hash}_u")
        }

        // 2. Partitioned 1P token
        if (sapisid1p != null && (sapisid != null || sapisid1p != primaryKey)) {
            val hash1p = computeSha1("$timestampSeconds $sapisid1p $origin")
            tokens.add("SAPISID1PHASH ${timestampSeconds}_${hash1p}_u")
        }

        // 3. Partitioned 3P token
        if (sapisid3p != null && (sapisid != null || sapisid3p != primaryKey)) {
            val hash3p = computeSha1("$timestampSeconds $sapisid3p $origin")
            tokens.add("SAPISID3PHASH ${timestampSeconds}_${hash3p}_u")
        }

        return tokens.joinToString(" ")
    }
}
