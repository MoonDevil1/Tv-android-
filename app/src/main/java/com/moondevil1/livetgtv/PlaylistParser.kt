package com.moondevil1.livetgtv

import org.json.JSONObject
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object PlaylistParser {
    fun parse(source: String): List<Channel> {
        val result = mutableListOf<Channel>()
        var name: String? = null
        var logo = ""
        var group = "Other"
        var headers = linkedMapOf<String, String>()
        var licenseUrl: String? = null
        var licenseType: String? = null
        var manifestType: String? = null
        var licenseHeaders = linkedMapOf<String, String>()
        var subtitleUrl: String? = null
        var subtitleLanguage: String? = null

        fun decode(value: String) = value
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")

        fun headerName(value: String): String = when (value.lowercase()) {
            "user-agent" -> "User-Agent"
            "referer", "referrer" -> "Referer"
            "origin" -> "Origin"
            "cookie" -> "Cookie"
            "authorization" -> "Authorization"
            else -> value
        }

        fun parseHeaders(value: String, target: MutableMap<String, String>) {
            value.split("&").forEach { item ->
                val split = item.indexOf('=')
                if (split > 0) {
                    val key = runCatching { URLDecoder.decode(item.substring(0, split), StandardCharsets.UTF_8.name()) }.getOrDefault(item.substring(0, split))
                    val headerValue = runCatching { URLDecoder.decode(item.substring(split + 1), StandardCharsets.UTF_8.name()) }.getOrDefault(item.substring(split + 1))
                    target[headerName(key)] = decode(headerValue)
                }
            }
        }

        for (raw in source.removePrefix("\uFEFF").lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue

            when {
                line.startsWith("#EXTINF:") -> {
                    val attrs = Regex("""([\\w-]+)="([^"]*)"""").findAll(line)
                        .associate { it.groupValues[1] to decode(it.groupValues[2]) }
                    val comma = line.indexOf(',')
                    name = if (comma >= 0) line.substring(comma + 1).trim()
                    else attrs["tvg-name"] ?: "Unknown channel"
                    logo = attrs["tvg-logo"].orEmpty()
                    group = attrs["group-title"] ?: "Other"
                    headers = linkedMapOf()
                    licenseUrl = null
                    licenseType = null
                    manifestType = null
                    licenseHeaders = linkedMapOf()
                    subtitleUrl = null
                    subtitleLanguage = null
                }

                line.startsWith("#EXTVLCOPT:http-") -> {
                    val pair = line.removePrefix("#EXTVLCOPT:http-").split("=", limit = 2)
                    if (pair.size == 2) headers[headerName(pair[0])] = decode(pair[1])
                }

                line.startsWith("#EXTVLCOPT:sub-file=") ->
                    subtitleUrl = decode(line.substringAfter("sub-file=")).trim('"')

                line.startsWith("#EXTVLCOPT:sub-language=") ->
                    subtitleLanguage = decode(line.substringAfter("sub-language="))

                line.startsWith("#EXTHTTP:") -> {
                    runCatching {
                        val json = JSONObject(line.removePrefix("#EXTHTTP:"))
                        json.keys().forEach { key -> headers[headerName(key)] = json.optString(key) }
                    }
                }

                line.startsWith("#KODIPROP:") -> {
                    val pair = line.removePrefix("#KODIPROP:").split("=", limit = 2)
                    if (pair.size == 2) {
                        val value = decode(pair[1])
                        when (pair[0].trim()) {
                            "inputstream.adaptive.license_key" -> {
                                // Kodi syntax: license URL|request headers|optional flags.
                                val parts = value.split('|')
                                val candidate = parts.firstOrNull()?.trim().orEmpty()
                                licenseUrl = candidate.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                                if (parts.size > 1) parseHeaders(parts[1], licenseHeaders)
                            }
                            "inputstream.adaptive.license_type" -> licenseType = value
                            "inputstream.adaptive.manifest_type" -> manifestType = value
                        }
                    }
                }

                !line.startsWith("#") && name != null -> {
                    result += Channel(
                        name = name!!,
                        logo = logo,
                        group = group,
                        url = decode(line),
                        headers = headers.toMap(),
                        licenseUrl = licenseUrl,
                        licenseType = licenseType,
                        manifestType = manifestType,
                        licenseHeaders = licenseHeaders.toMap(),
                        subtitleUrl = subtitleUrl,
                        subtitleLanguage = subtitleLanguage
                    )
                    name = null
                }
            }
        }
        return result
    }
}
