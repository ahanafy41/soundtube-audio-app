package com.soundtube.app.data.csv

import com.soundtube.app.data.local.Subscription
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Robust CSV parser for Google Takeout subscriptions.csv files.
 * Compliant with RFC 4180 (handles commas inside quotes, escaped quotes, and alternate column orders).
 */
object TakeoutCsvParser {

    /**
     * Parses an InputStream containing subscriptions.csv.
     */
    fun parse(inputStream: InputStream): List<Subscription> {
        val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
        return parseLines(reader.lineSequence())
    }

    /**
     * Parses raw CSV string content.
     */
    fun parse(csvContent: String): List<Subscription> {
        return parseLines(csvContent.lineSequence())
    }

    private fun parseLines(lines: Sequence<String>): List<Subscription> {
        val subscriptions = mutableListOf<Subscription>()
        val seenIds = mutableSetOf<String>()

        var idIndex = 0
        var titleIndex = 2
        var urlIndex = 1
        var isFirstLine = true

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isBlank()) continue

            val tokens = parseCsvLine(line)
            if (tokens.isEmpty()) continue

            if (isFirstLine) {
                isFirstLine = false
                val lowerTokens = tokens.map { it.lowercase().trim() }
                val detectedId = lowerTokens.indexOfFirst { it.contains("channel id") || it == "id" }
                val detectedTitle = lowerTokens.indexOfFirst { it.contains("channel title") || it.contains("title") || it == "name" }
                val detectedUrl = lowerTokens.indexOfFirst { it.contains("channel url") || it.contains("url") }

                if (detectedId != -1) {
                    idIndex = detectedId
                    if (detectedTitle != -1) titleIndex = detectedTitle
                    if (detectedUrl != -1) urlIndex = detectedUrl
                    // Header line skipped
                    continue
                }
            }

            var channelId = tokens.getOrNull(idIndex)?.trim().orEmpty()
            var channelTitle = tokens.getOrNull(titleIndex)?.trim().orEmpty()
            val channelUrl = tokens.getOrNull(urlIndex)?.trim().orEmpty()

            // If channelId is empty or looks like a URL, attempt extracting from url
            if (channelId.isBlank() && channelUrl.isNotBlank()) {
                channelId = extractChannelIdFromUrl(channelUrl)
            } else if (channelId.startsWith("http://") || channelId.startsWith("https://")) {
                val extracted = extractChannelIdFromUrl(channelId)
                if (extracted.isNotBlank()) {
                    channelId = extracted
                }
            }

            // Fallback for title if only 2 columns exist
            if (channelTitle.isBlank() && tokens.size == 2 && urlIndex != 1) {
                channelTitle = tokens[1].trim()
            } else if (channelTitle.isBlank() && channelUrl.isNotBlank() && !channelUrl.startsWith("http")) {
                channelTitle = channelUrl
            }

            if (channelTitle.isBlank()) {
                channelTitle = "قناة $channelId"
            }

            if (channelId.isNotBlank() && seenIds.add(channelId)) {
                subscriptions.add(
                    Subscription(
                        channelId = channelId,
                        channelTitle = channelTitle,
                        avatarUrl = null
                    )
                )
            }
        }

        return subscriptions
    }

    private fun extractChannelIdFromUrl(url: String): String {
        val trimmed = url.trim().removeSuffix("/")
        val marker = "/channel/"
        val idx = trimmed.indexOf(marker)
        return if (idx != -1) {
            trimmed.substring(idx + marker.length).substringBefore("/")
        } else {
            ""
        }
    }

    /**
     * Splits a CSV line into tokens, respecting double-quote delimiters and escaped double quotes ("").
     */
    fun parseCsvLine(line: String): List<String> {
        val tokens = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0

        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' -> {
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                        // Escaped quote: ""
                        sb.append('"')
                        i++ // Skip next quote
                    } else {
                        // Toggle quote mode
                        inQuotes = !inQuotes
                    }
                }
                c == ',' && !inQuotes -> {
                    tokens.add(sb.toString())
                    sb.clear()
                }
                else -> {
                    sb.append(c)
                }
            }
            i++
        }
        tokens.add(sb.toString())
        return tokens
    }
}
