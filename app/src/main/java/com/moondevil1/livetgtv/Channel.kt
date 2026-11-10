package com.moondevil1.livetgtv

data class Channel(
    val name: String,
    val logo: String,
    val group: String,
    val url: String,
    val headers: Map<String, String>,
    val licenseUrl: String? = null,
    val licenseType: String? = null,
    val manifestType: String? = null,
    val licenseHeaders: Map<String, String> = emptyMap(),
    val subtitleUrl: String? = null,
    val subtitleLanguage: String? = null
)
