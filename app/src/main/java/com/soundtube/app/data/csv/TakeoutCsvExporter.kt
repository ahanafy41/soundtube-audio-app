package com.soundtube.app.data.csv

import com.soundtube.app.data.local.Subscription
import java.io.OutputStream
import java.io.OutputStreamWriter

/**
 * Exporter generating standard RFC 4180 Google Takeout subscriptions.csv files from local subscriptions.
 */
object TakeoutCsvExporter {

    private const val HEADER = "Channel Id,Channel Url,Channel Title"

    /**
     * Converts a list of subscriptions into a standard CSV string.
     */
    fun export(subscriptions: List<Subscription>): String {
        val sb = StringBuilder()
        sb.append(HEADER).append("\r\n")

        for (sub in subscriptions) {
            val escapedTitle = escapeCsvField(sub.channelTitle)
            val channelUrl = "https://www.youtube.com/channel/${sub.channelId}"
            sb.append("${sub.channelId},$channelUrl,\"$escapedTitle\"\r\n")
        }

        return sb.toString()
    }

    /**
     * Writes CSV data directly to an OutputStream (e.g. from Storage Access Framework CreateDocument).
     */
    fun exportToOutputStream(subscriptions: List<Subscription>, outputStream: OutputStream) {
        val writer = OutputStreamWriter(outputStream, Charsets.UTF_8)
        writer.write(export(subscriptions))
        writer.flush()
    }

    private fun escapeCsvField(value: String): String {
        return value.replace("\"", "\"\"")
    }
}
