package com.soundtube.app.data.csv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TakeoutCsvParserTest {

    @Test
    fun parse_standardGoogleTakeoutFormat_returnsSubscriptions() {
        val csv = """
            Channel Id,Channel Url,Channel Title
            UC_x5XG1OV2P6uZZ5FSM9Ttw,http://www.youtube.com/channel/UC_x5XG1OV2P6uZZ5FSM9Ttw,Google Developers
            UC29ju8bIPH5as8OGnQzwJyA,http://www.youtube.com/channel/UC29ju8bIPH5as8OGnQzwJyA,Traversy Media
        """.trimIndent()

        val results = TakeoutCsvParser.parse(csv)

        assertEquals(2, results.size)
        assertEquals("UC_x5XG1OV2P6uZZ5FSM9Ttw", results[0].channelId)
        assertEquals("Google Developers", results[0].channelTitle)

        assertEquals("UC29ju8bIPH5as8OGnQzwJyA", results[1].channelId)
        assertEquals("Traversy Media", results[1].channelTitle)
    }

    @Test
    fun parse_withCommasAndQuotesInTitle_handlesQuotesCorrectly() {
        val csv = """
            Channel Id,Channel Url,Channel Title
            UC123,http://www.youtube.com/channel/UC123,"Tech, Audio, and Gadgets"
            UC456,http://www.youtube.com/channel/UC456,"The ""Master"" Class"
        """.trimIndent()

        val results = TakeoutCsvParser.parse(csv)

        assertEquals(2, results.size)
        assertEquals("UC123", results[0].channelId)
        assertEquals("Tech, Audio, and Gadgets", results[0].channelTitle)

        assertEquals("UC456", results[1].channelId)
        assertEquals("The \"Master\" Class", results[1].channelTitle)
    }

    @Test
    fun parse_withoutHeader_parsesFirstLineAsSubscription() {
        val csv = """
            UC789,https://www.youtube.com/channel/UC789,Direct Channel
            UC999,https://www.youtube.com/channel/UC999,Second Channel
        """.trimIndent()

        val results = TakeoutCsvParser.parse(csv)

        assertEquals(2, results.size)
        assertEquals("UC789", results[0].channelId)
        assertEquals("Direct Channel", results[0].channelTitle)
    }

    @Test
    fun parse_extractsChannelIdFromUrl_whenIdColumnIsEmpty() {
        val csv = """
            Channel Id,Channel Url,Channel Title
            ,https://www.youtube.com/channel/UC_auto_extracted,Recovered Channel
        """.trimIndent()

        val results = TakeoutCsvParser.parse(csv)

        assertEquals(1, results.size)
        assertEquals("UC_auto_extracted", results[0].channelId)
        assertEquals("Recovered Channel", results[0].channelTitle)
    }

    @Test
    fun parse_handlesDuplicateChannelIds_deduplicatesGracefully() {
        val csv = """
            Channel Id,Channel Url,Channel Title
            UCDUPLICATE,http://www.youtube.com/channel/UCDUPLICATE,First Name
            UCDUPLICATE,http://www.youtube.com/channel/UCDUPLICATE,Second Name
        """.trimIndent()

        val results = TakeoutCsvParser.parse(csv)

        assertEquals(1, results.size)
        assertEquals("UCDUPLICATE", results[0].channelId)
    }

    @Test
    fun parse_withBlankLinesAndMalformedData_ignoresSafely() {
        val csv = """
            
            Channel Id,Channel Url,Channel Title
            
            UCVALID,https://www.youtube.com/channel/UCVALID,Valid Title
            
        """.trimIndent()

        val results = TakeoutCsvParser.parse(csv)

        assertEquals(1, results.size)
        assertEquals("UCVALID", results[0].channelId)
    }

    @Test
    fun parseCsvLine_handlesEscapedQuotesProperly() {
        val line = "\"UC123\",\"http://url\",\"Title with \"\"quotes\"\" and , commas\""
        val tokens = TakeoutCsvParser.parseCsvLine(line)

        assertEquals(3, tokens.size)
        assertEquals("UC123", tokens[0])
        assertEquals("http://url", tokens[1])
        assertEquals("Title with \"quotes\" and , commas", tokens[2])
    }
}
