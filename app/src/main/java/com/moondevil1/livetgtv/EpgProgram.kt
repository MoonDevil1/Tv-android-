package com.moondevil1.livetgtv

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class EpgProgram(val channelId: String, val title: String, val description: String, val start: Long, val stop: Long, val image: String)

object EpgParser {
    fun parse(xml: String): List<EpgProgram> {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(StringReader(xml))
        val programs = mutableListOf<EpgProgram>()
        var channel = ""
        var title = ""
        var description = ""
        var image = ""
        var start = 0L
        var stop = 0L
        var activeTag = ""
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name.lowercase(Locale.ROOT)) {
                    "programme" -> {
                        channel = parser.getAttributeValue(null, "channel").orEmpty()
                        start = parseTime(parser.getAttributeValue(null, "start").orEmpty())
                        stop = parseTime(parser.getAttributeValue(null, "stop").orEmpty())
                        title = ""; description = ""; image = ""
                    }
                    "title", "desc" -> activeTag = parser.name.lowercase(Locale.ROOT)
                    "icon" -> image = parser.getAttributeValue(null, "src").orEmpty()
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> when (activeTag) {
                    "title" -> title += parser.text.orEmpty()
                    "desc" -> description += parser.text.orEmpty()
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name.equals("title", true) || parser.name.equals("desc", true)) activeTag = ""
                    if (parser.name.equals("programme", true) && channel.isNotBlank() && title.isNotBlank() && stop > start) programs += EpgProgram(channel, title.trim(), description.trim(), start, stop, image)
                }
            }
            event = parser.next()
        }
        return programs
    }

    private fun parseTime(value: String): Long {
        if (value.isBlank()) return 0L
        for (pattern in listOf("yyyyMMddHHmmss Z", "yyyyMMddHHmmss")) {
            try {
                val format = SimpleDateFormat(pattern, Locale.US).apply {
                    isLenient = false
                    if (pattern == "yyyyMMddHHmmss") timeZone = TimeZone.getDefault()
                }
                return format.parse(value.trim())?.time ?: 0L
            } catch (_: Exception) { }
        }
        return 0L
    }
}
