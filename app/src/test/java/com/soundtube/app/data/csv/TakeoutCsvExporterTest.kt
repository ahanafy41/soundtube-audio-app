package com.soundtube.app.data.csv

import com.soundtube.app.data.local.Subscription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class TakeoutCsvExporterTest {

    @Test
    fun export_generatesValidCsvHeaderAndRows() {
        val subscriptions = listOf(
            Subscription(channelId = "UC1", channelTitle = "First Channel"),
            Subscription(channelId = "UC2", channelTitle = "Second Channel with, comma")
        )

        val csv = TakeoutCsvExporter.export(subscriptions)

        assertTrue(csv.startsWith("Channel Id,Channel Url,Channel Title"))
        assertTrue(csv.contains("UC1,https://www.youtube.com/channel/UC1,\"First Channel\""))
        assertTrue(csv.contains("UC2,https://www.youtube.com/channel/UC2,\"Second Channel with, comma\""))
    }

    @Test
    fun export_escapesDoubleQuotesInTitles() {
        val subscriptions = listOf(
            Subscription(channelId = "UC_QUOTE", channelTitle = "The \"Best\" Channel")
        )

        val csv = TakeoutCsvExporter.export(subscriptions)

        assertTrue(csv.contains("UC_QUOTE,https://www.youtube.com/channel/UC_QUOTE,\"The \"\"Best\"\" Channel\""))
    }

    @Test
    fun exportToOutputStream_writesValidBytes() {
        val subscriptions = listOf(
            Subscription(channelId = "UC_STREAM", channelTitle = "Stream Channel")
        )
        val baos = ByteArrayOutputStream()
        TakeoutCsvExporter.exportToOutputStream(subscriptions, baos)

        val output = baos.toString(Charsets.UTF_8.name())
        assertTrue(output.contains("UC_STREAM"))
    }

    @Test
    fun roundTrip_exportThenParse_preservesData() {
        val original = listOf(
            Subscription(channelId = "UC_A", channelTitle = "Channel A"),
            Subscription(channelId = "UC_B", channelTitle = "Channel with \"quotes\" and , commas")
        )

        val csv = TakeoutCsvExporter.export(original)
        val parsed = TakeoutCsvParser.parse(csv)

        assertEquals(original.size, parsed.size)
        assertEquals(original[0].channelId, parsed[0].channelId)
        assertEquals(original[0].channelTitle, parsed[0].channelTitle)

        assertEquals(original[1].channelId, parsed[1].channelId)
        assertEquals(original[1].channelTitle, parsed[1].channelTitle)
    }
}
