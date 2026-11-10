package com.moondevil1.livetgtv

import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

private const val PLAYLIST_URL = "https://livetgtv.lovable.app/api/public/playlist.m3u"
private val WIDEVINE_UUID = UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed")
private val CLEARKEY_UUID = UUID.fromString("e2719d58-a985-b3c9-781a-b030af78d30e")
private val PLAYREADY_UUID = UUID.fromString("9a04f079-9840-4286-ab92-e65be0885f95")

class MainActivity : ComponentActivity() {
    @OptIn(UnstableApi::class)
    var pipPlayer: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LiveTvScreen() }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val activePlayer = pipPlayer
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            activePlayer?.isPlaying == true &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        ) {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build()
            )
        }
    }
}


private fun buildClearKeyResponse(value: String): ByteArray {
    var raw = value.trim().trim('"')
    if (raw.startsWith("data:application/json;base64,", true)) {
        raw = String(Base64.decode(raw.substringAfter(','), Base64.DEFAULT), Charsets.UTF_8)
    }
    if (raw.startsWith("{")) {
        val json = org.json.JSONObject(raw)
        if (json.has("keys")) return raw.toByteArray(Charsets.UTF_8)
        val keys = org.json.JSONArray()
        val iterator = json.keys()
        while (iterator.hasNext()) {
            val kid = iterator.next()
            val key = json.optString(kid)
            keys.put(org.json.JSONObject()
                .put("kty", "oct")
                .put("kid", hexToBase64Url(kid))
                .put("k", hexToBase64Url(key)))
        }
        if (keys.length() == 0) throw IllegalArgumentException("ClearKey JSON contains no usable keys.")
        return org.json.JSONObject().put("keys", keys).put("type", "temporary").toString().toByteArray(Charsets.UTF_8)
    }
    val entries = org.json.JSONArray()
    raw.split(',').forEach { pair ->
        val parts = pair.trim().split(':', limit = 2)
        if (parts.size != 2) throw IllegalArgumentException("Invalid ClearKey value. Expected KID:KEY in hexadecimal.")
        entries.put(org.json.JSONObject()
            .put("kty", "oct")
            .put("kid", hexToBase64Url(parts[0].trim()))
            .put("k", hexToBase64Url(parts[1].trim())))
    }
    if (entries.length() == 0) throw IllegalArgumentException("No ClearKey key pairs were provided.")
    return org.json.JSONObject().put("keys", entries).put("type", "temporary").toString().toByteArray(Charsets.UTF_8)
}

private fun hexToBase64Url(hex: String): String {
    require(hex.length == 32 && hex.all { it.digitToIntOrNull(16) != null }) {
        "ClearKey KID and KEY must each be 32 hexadecimal characters."
    }
    val bytes = ByteArray(16) { index -> hex.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
}

@OptIn(UnstableApi::class)
@Composable
private fun LiveTvScreen() {
    val context = LocalContext.current
    val activity = context as? MainActivity
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("livetgtv_player", android.content.Context.MODE_PRIVATE) }
    var channels by remember { mutableStateOf<List<Channel>>(emptyList()) }
    var selected by remember { mutableStateOf<Channel?>(null) }
    var search by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("All") }
    var mode by remember { mutableStateOf("Channels") }
    var error by remember { mutableStateOf<String?>(null) }
    var playbackError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var favorites by remember { mutableStateOf(prefs.getStringSet("favorites", emptySet()).orEmpty().toSet()) }
    var history by remember { mutableStateOf(prefs.getStringSet("history", emptySet()).orEmpty().toList()) }
    var speed by remember { mutableFloatStateOf(1f) }
    var showAudioDialog by remember { mutableStateOf(false) }

    val trackSelector = remember { DefaultTrackSelector(context) }
    val player = remember {
        ExoPlayer.Builder(context)
            .setTrackSelector(trackSelector)
            .build()
    }
    val playerListener = remember {
        object : Player.Listener {
            override fun onPlayerError(exception: PlaybackException) {
                val detail = exception.cause?.message ?: exception.message ?: "The stream could not be opened."
                playbackError = when (exception.errorCode) {
                    PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED ->
                        "DRM license request failed. Check the playlist license URL, license headers, account authorization and provider availability. Details: $detail"
                    PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR ->
                        "This device could not initialize the required DRM system. Details: $detail"
                    else -> "Playback failed (${exception.errorCodeName}): $detail"
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) playbackError = null
            }
        }
    }

    suspend fun refresh() {
        loading = true
        error = null
        try {
            val text = withContext(Dispatchers.IO) {
                val connection = URL(PLAYLIST_URL).openConnection() as HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 20000
                connection.setRequestProperty("User-Agent", "LiveTGTV-Android/1.1")
                try {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } finally {
                    connection.disconnect()
                }
            }
            channels = PlaylistParser.parse(text)
            if (channels.isEmpty()) error = "Playlist returned no channels."
            if (selected == null) {
                val lastUrl = prefs.getString("last_channel", null)
                selected = channels.firstOrNull { it.url == lastUrl }
            }
        } catch (exception: Exception) {
            error = exception.message ?: "Could not load playlist."
        } finally {
            loading = false
        }
    }

    fun play(channel: Channel) {
        selected = channel
        error = null
        playbackError = null
        prefs.edit().putString("last_channel", channel.url).apply()
        history = (listOf(channel.url) + history.filterNot { it == channel.url }).take(80)
        prefs.edit().putStringSet("history", history.toSet()).apply()
        try {
            val streamUrl = channel.url.substringBefore('?').lowercase()
            val mimeType = when {
                channel.manifestType?.contains("mpd", true) == true ||
                    channel.manifestType?.contains("dash", true) == true ||
                    streamUrl.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
                channel.manifestType?.contains("hls", true) == true ||
                    streamUrl.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
                channel.manifestType?.contains("smooth", true) == true ||
                    channel.manifestType?.contains("ss", true) == true ||
                    streamUrl.endsWith(".ism/manifest") -> MimeTypes.APPLICATION_SS
                else -> null
            }

            val drmUuid = when (channel.licenseType?.lowercase()?.substringBefore(';')?.trim()) {
                "com.widevine.alpha", "widevine", "widevine drm" -> WIDEVINE_UUID
                "clearkey", "org.w3.clearkey", "com.w3.clearkey" -> CLEARKEY_UUID
                "com.microsoft.playready", "playready" -> PLAYREADY_UUID
                null, "" -> WIDEVINE_UUID.takeIf { !channel.licenseUrl.isNullOrBlank() }
                else -> null
            }
            if (!channel.licenseUrl.isNullOrBlank() && drmUuid == null) {
                throw IllegalArgumentException("Unsupported DRM license_type: ${channel.licenseType}")
            }
            val staticClearKey = if (drmUuid == CLEARKEY_UUID && !channel.licenseKeyData.isNullOrBlank()) {
                buildClearKeyResponse(channel.licenseKeyData)
            } else null
            if (!channel.licenseType.isNullOrBlank() && channel.licenseUrl.isNullOrBlank() && staticClearKey == null) {
                throw IllegalArgumentException(
                    if (drmUuid == CLEARKEY_UUID) "The playlist declares ClearKey but contains no static KID:KEY/JWK data or license URL."
                    else "The playlist declares DRM (${channel.licenseType}) but does not provide a license URL."
                )
            }

            val item = MediaItem.Builder().setUri(channel.url).apply {
                if (mimeType != null) setMimeType(mimeType)
                if (channel.subtitleUrl?.isNotBlank() == true) {
                    val subtitleMime = when (channel.subtitleUrl.substringBefore('?').substringAfterLast('.', "").lowercase()) {
                        "srt" -> MimeTypes.APPLICATION_SUBRIP
                        "vtt" -> MimeTypes.TEXT_VTT
                        "ass", "ssa" -> MimeTypes.TEXT_SSA
                        "ttml", "xml" -> MimeTypes.APPLICATION_TTML
                        else -> MimeTypes.TEXT_VTT
                    }
                    setSubtitleConfigurations(
                        listOf(
                            MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(channel.subtitleUrl))
                                .setMimeType(subtitleMime)
                                .setLanguage(channel.subtitleLanguage ?: "und")
                                .build()
                        )
                    )
                }
                if (!channel.licenseUrl.isNullOrBlank() && drmUuid != null) {
                    setDrmConfiguration(
                        MediaItem.DrmConfiguration.Builder(drmUuid)
                            .setLicenseUri(channel.licenseUrl!!)
                            .setLicenseRequestHeaders(channel.licenseHeaders.ifEmpty { channel.headers })
                            .setMultiSession(true)
                            .build()
                    )
                }
            }.build()

            val dataSourceFactory = DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(30000)
                .setDefaultRequestProperties(channel.headers)
            player.stop()
            val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
            if (staticClearKey != null) {
                val localDrmManager = DefaultDrmSessionManager.Builder()
                    .setUuidAndExoMediaDrmProvider(CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                    .build(LocalMediaDrmCallback(staticClearKey))
                mediaSourceFactory.setDrmSessionManagerProvider { localDrmManager }
            }
            player.setMediaSource(mediaSourceFactory.createMediaSource(item))
            val resumeAt = prefs.getLong("position:${channel.url}", 0L)
            if (resumeAt > 5000L) player.seekTo(resumeAt)
            player.prepare()
            player.playWhenReady = true
            speed = 1f
            player.setPlaybackSpeed(1f)
        } catch (exception: Exception) {
            playbackError = exception.message ?: "Playback could not start."
        }
    }

    LaunchedEffect(Unit) { refresh() }
    LaunchedEffect(selected?.url) {
        while (selected != null) {
            val current = selected
            if (current != null && player.currentPosition > 0L) {
                prefs.edit().putLong("position:${current.url}", player.currentPosition).apply()
            }
            delay(5000)
        }
    }
    DisposableEffect(player, playerListener) {
        activity?.pipPlayer = player
        player.addListener(playerListener)
        onDispose {
            activity?.pipPlayer = null
            player.removeListener(playerListener)
            player.release()
        }
    }

    val visibleChannels = when (mode) {
        "Favorites" -> channels.filter { favorites.contains(it.url) }
        "History" -> history.mapNotNull { url -> channels.firstOrNull { it.url == url } }
        else -> channels
    }.filter {
        (category == "All" || it.group == category) &&
            (search.isBlank() || it.name.contains(search, true) || it.group.contains(search, true))
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF00DCCB),
            background = Color(0xFF080B16),
            surface = Color(0xFF111827)
        )
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(R.drawable.ic_livetgtv),
                    contentDescription = "LiveTGTV",
                    modifier = Modifier.size(44.dp),
                    contentScale = ContentScale.Fit
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("LiveTGTV", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                    Text("LIVE CHANNELS", style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = { scope.launch { refresh() } }) { Text("Refresh") }
            }

            selected?.let { channel ->
                AndroidView(
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                    factory = { viewContext ->
                        PlayerView(viewContext).apply {
                            this.player = player
                            useController = true
                            keepScreenOn = true
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                        }
                    },
                    update = { it.player = player }
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(channel.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        val updated = favorites.toMutableSet()
                        if (!updated.add(channel.url)) updated.remove(channel.url)
                        favorites = updated
                        prefs.edit().putStringSet("favorites", updated).apply()
                    }) {
                        Text(if (favorites.contains(channel.url)) "★" else "☆", color = MaterialTheme.colorScheme.primary)
                    }
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TextButton(onClick = { showTrackDialog(context, "Video quality", player, C.TRACK_TYPE_VIDEO) }) { Text("Quality") }
                    TextButton(onClick = { showAudioDialog = true }) { Text("Audio") }
                    TextButton(onClick = { showTrackDialog(context, "Subtitles", player, C.TRACK_TYPE_TEXT) }) { Text("Subtitles") }
                    TextButton(onClick = {
                        speed = when (speed) { 0.5f -> 0.75f; 0.75f -> 1f; 1f -> 1.25f; 1.25f -> 1.5f; 1.5f -> 2f; else -> 0.5f }
                        player.setPlaybackSpeed(speed)
                    }) { Text("${speed}x") }
                    TextButton(onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            activity?.enterPictureInPictureMode(
                                PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
                            )
                        }
                    }) { Text("PiP") }
                }
                playbackError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }

            if (showAudioDialog) {
                val audioGroups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                AlertDialog(
                    onDismissRequest = { showAudioDialog = false },
                    title = { Text("Audio track") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (audioGroups.isEmpty()) {
                                Text("No selectable audio tracks are exposed by this stream.")
                            } else {
                                audioGroups.forEach { group ->
                                    for (trackIndex in 0 until group.length) {
                                        val format = group.getTrackFormat(trackIndex)
                                        val language = format.language?.takeIf { it.isNotBlank() && it != "und" } ?: "Unknown language"
                                        val label = format.label?.takeIf { it.isNotBlank() }
                                        val detail = listOfNotNull(language, label, format.channelCount.takeIf { it > 0 }?.let { "${it}ch" }).distinct().joinToString(" · ")
                                        val selectedTrack = group.isTrackSelected(trackIndex)
                                        TextButton(
                                            onClick = {
                                                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                                                    .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                                                    .addOverride(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
                                                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                                                    .build()
                                                showAudioDialog = false
                                            },
                                            modifier = Modifier.fillMaxWidth()
                                        ) { Text((if (selectedTrack) "✓ " else "") + detail) }
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = { TextButton(onClick = { showAudioDialog = false }) { Text("Close") } }
                )
            }
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Search channels") },
                singleLine = true
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Channels", "Favorites", "History").forEach { item ->
                    FilterChip(selected = mode == item, onClick = { mode = item; category = "All" }, label = { Text(item) })
                }
            }
            val groups = listOf("All") + channels.map { it.group }.distinct().sorted()
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                groups.forEach { group ->
                    FilterChip(selected = category == group, onClick = { category = group }, label = { Text(group) })
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${visibleChannels.size} channels", style = MaterialTheme.typography.labelMedium)
                if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (mode == "Favorites" && visibleChannels.isEmpty()) {
                Text("No favorites yet. Tap ☆ beside a channel to save it.")
            } else if (mode == "History" && visibleChannels.isEmpty()) {
                Text("Recently played channels will appear here.")
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(visibleChannels, key = { it.url }) { channel ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable { play(channel) },
                        shape = RoundedCornerShape(12.dp),
                        color = if (selected?.url == channel.url) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
                    ) {
                        Row(
                            Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (channel.logo.isNotBlank()) {
                                AsyncImage(
                                    model = channel.logo,
                                    contentDescription = "${channel.name} logo",
                                    modifier = Modifier.size(width = 72.dp, height = 46.dp),
                                    contentScale = ContentScale.Fit
                                )
                            } else {
                                Image(
                                    painter = painterResource(R.drawable.ic_livetgtv),
                                    contentDescription = null,
                                    modifier = Modifier.size(44.dp)
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(channel.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    channel.group + if (!channel.licenseUrl.isNullOrBlank()) " · DRM" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (favorites.contains(channel.url)) Text("★", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}
