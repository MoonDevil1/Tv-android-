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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.window.Dialog
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
import androidx.media3.ui.TrackSelectionDialogBuilder
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
    var showChannelDrawer by remember { mutableStateOf(false) }
    var isFullscreen by remember { mutableStateOf(false) }
    var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }

    val trackSelector = remember { DefaultTrackSelector(context) }
    val player = remember {
        ExoPlayer.Builder(context)
            .setTrackSelector(trackSelector)
            .build()
    }
    SideEffect {
        activity?.window?.let { window ->
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            if (isFullscreen) {
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
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
            primary = Color(0xFF22D3EE),
            onPrimary = Color(0xFF04131A),
            background = Color(0xFF070B14),
            surface = Color(0xFF111827),
            surfaceVariant = Color(0xFF1A2435),
            onSurface = Color(0xFFF1F5F9),
            onSurfaceVariant = Color(0xFF94A3B8),
            outline = Color(0xFF293548)
        )
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF070B14))
                .then(if (isFullscreen) Modifier else Modifier.padding(horizontal = 14.dp, vertical = 10.dp)),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (!isFullscreen) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(42.dp).background(
                            brush = androidx.compose.ui.graphics.Brush.linearGradient(
                                listOf(Color(0xFF22D3EE), Color(0xFF2563EB))
                            ),
                            shape = RoundedCornerShape(14.dp)
                        ),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_livetgtv),
                            contentDescription = "LiveTGTV",
                            modifier = Modifier.size(30.dp),
                            contentScale = ContentScale.Fit
                        )
                    }
                    Spacer(Modifier.width(11.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("LiveTGTV", style = MaterialTheme.typography.titleLarge, color = Color.White)
                            Surface(color = Color(0xFF083344), shape = RoundedCornerShape(5.dp)) {
                                Text("LIVE", modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp), color = Color(0xFF67E8F9), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Text("Your channels, all in one place", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                    }
                    IconButton(onClick = { scope.launch { refresh() } }, enabled = !loading) {
                        Text("↻", color = Color(0xFF67E8F9), style = MaterialTheme.typography.headlineMedium)
                    }
                }
            }

            selected?.let { channel ->
                val currentQueueIndex = visibleChannels.indexOfFirst { it.url == channel.url }
                val previousChannel = visibleChannels.getOrNull(currentQueueIndex - 1)
                val nextChannel = when {
                    currentQueueIndex >= 0 -> visibleChannels.getOrNull(currentQueueIndex + 1)
                    else -> visibleChannels.firstOrNull()
                }
                Box(Modifier.fillMaxWidth().then(if (isFullscreen) Modifier.weight(1f) else Modifier.aspectRatio(16f / 9f))) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { viewContext ->
                            android.view.LayoutInflater.from(viewContext)
                                .inflate(R.layout.livetgtv_player_host, null, false) as android.widget.FrameLayout
                        },
                        update = { host ->
                            val nativeView = host.findViewById<PlayerView>(R.id.livetgtv_player_view)
                            nativeView.player = player
                            nativeView.keepScreenOn = true
                            nativeView.resizeMode = resizeMode
                            nativeView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                            host.findViewById<android.widget.Button>(R.id.ltv_previous).apply {
                                isEnabled = previousChannel != null
                                setOnClickListener { previousChannel?.let { play(it) } }
                            }
                            host.findViewById<android.widget.Button>(R.id.ltv_next).apply {
                                isEnabled = nextChannel != null
                                setOnClickListener { nextChannel?.let { play(it) } }
                            }
                            host.findViewById<android.widget.Button>(R.id.ltv_channels).setOnClickListener { showChannelDrawer = true }
                            host.findViewById<android.widget.Button>(R.id.ltv_audio).setOnClickListener { showAudioDialog = true }
                            host.findViewById<android.widget.Button>(R.id.ltv_quality).setOnClickListener {
                                showTrackDialog(context, "Video quality", player, C.TRACK_TYPE_VIDEO)
                            }
                            host.findViewById<android.widget.Button>(R.id.ltv_subtitles).setOnClickListener {
                                showTrackDialog(context, "Subtitles", player, C.TRACK_TYPE_TEXT)
                            }
                            host.findViewById<android.widget.Button>(R.id.ltv_fit).apply {
                                text = when (resizeMode) {
                                    AspectRatioFrameLayout.RESIZE_MODE_FILL -> "Fill"
                                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Zoom"
                                    else -> "Fit"
                                }
                                setOnClickListener {
                                    resizeMode = when (resizeMode) {
                                        AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                                        AspectRatioFrameLayout.RESIZE_MODE_FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                                    }
                                    nativeView.resizeMode = resizeMode
                                    text = when (resizeMode) {
                                        AspectRatioFrameLayout.RESIZE_MODE_FILL -> "Fill"
                                        AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Zoom"
                                        else -> "Fit"
                                    }
                                }
                            }
                            host.findViewById<android.widget.Button>(R.id.ltv_fullscreen).apply {
                                text = if (isFullscreen) "Exit full screen" else "Full screen"
                                setOnClickListener { isFullscreen = !isFullscreen }
                            }
                        }
                    )
                }
                if (!isFullscreen) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(channel.name, style = MaterialTheme.typography.titleMedium, color = Color.White)
                            Text(channel.group, style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                        }
                        IconButton(onClick = {
                            val updated = favorites.toMutableSet()
                            if (!updated.add(channel.url)) updated.remove(channel.url)
                            favorites = updated
                            prefs.edit().putStringSet("favorites", updated).apply()
                        }) {
                            Text(if (favorites.contains(channel.url)) "★" else "☆", color = Color(0xFFFBBF24), style = MaterialTheme.typography.headlineSmall)
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("Speed" to "${speed}x", "PiP" to "⧉").forEach { (label, value) ->
                            Surface(
                                color = Color(0xFF111827),
                                shape = RoundedCornerShape(10.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF263449)),
                                modifier = Modifier.clickable {
                                    if (label == "Speed") {
                                        speed = when (speed) { 0.5f -> 0.75f; 0.75f -> 1f; 1f -> 1.25f; 1.25f -> 1.5f; 1.5f -> 2f; else -> 0.5f }
                                        player.setPlaybackSpeed(speed)
                                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        activity?.enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
                                    }
                                }
                            ) {
                                Text(if (label == "Speed") value else "⧉  Picture in picture", modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = Color(0xFFCBD5E1), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                    playbackError?.let {
                        Surface(color = Color(0xFF3B1720), shape = RoundedCornerShape(10.dp)) {
                            Text(it, Modifier.fillMaxWidth().padding(10.dp), color = Color(0xFFFDA4AF), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            if (!isFullscreen) {
                if (showAudioDialog) {
                    val audioGroups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                    AlertDialog(
                        onDismissRequest = { showAudioDialog = false },
                        title = { Text("Audio track") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (audioGroups.isEmpty()) Text("No selectable audio tracks are exposed by this stream.")
                                else audioGroups.forEach { group ->
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
                        },
                        confirmButton = { TextButton(onClick = { showAudioDialog = false }) { Text("Close") } }
                    )
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF101827),
                    shape = RoundedCornerShape(18.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B))
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("Explore channels", style = MaterialTheme.typography.titleLarge, color = Color.White)
                                Text("Find something worth watching", style = MaterialTheme.typography.bodySmall, color = Color(0xFF94A3B8))
                            }
                            Surface(color = Color(0xFF082F49), shape = RoundedCornerShape(9.dp)) {
                                Text("${channels.size} TOTAL", Modifier.padding(horizontal = 9.dp, vertical = 6.dp), color = Color(0xFF67E8F9), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        OutlinedTextField(
                            value = search,
                            onValueChange = { search = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Search channels by name or category…") },
                            leadingIcon = { Text("⌕", color = Color(0xFF67E8F9), style = MaterialTheme.typography.headlineSmall) },
                            trailingIcon = {
                                if (search.isNotBlank()) TextButton(onClick = { search = "" }) { Text("Clear") }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(13.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF22D3EE),
                                unfocusedBorderColor = Color(0xFF293548),
                                focusedContainerColor = Color(0xFF0B1220),
                                unfocusedContainerColor = Color(0xFF0B1220),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedPlaceholderColor = Color(0xFF64748B),
                                unfocusedPlaceholderColor = Color(0xFF64748B)
                            )
                        )
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            listOf("Channels", "Favorites", "History").forEach { item ->
                                val active = mode == item
                                Surface(
                                    onClick = { mode = item; category = "All" },
                                    color = if (active) Color(0xFF22D3EE) else Color(0xFF1A2435),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text(
                                        (if (item == "Favorites") "★  " else if (item == "History") "◷  " else "▦  ") + item,
                                        Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                                        color = if (active) Color(0xFF04202A) else Color(0xFFCBD5E1),
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                }
                            }
                        }
                    }
                }

                val groups = listOf("All") + channels.map { it.group }.distinct().sorted()
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    groups.forEach { group ->
                        val active = category == group
                        Surface(
                            onClick = { category = group },
                            color = if (active) Color(0xFF164E63) else Color(0xFF111827),
                            shape = RoundedCornerShape(9.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, if (active) Color(0xFF0891B2) else Color(0xFF253247))
                        ) {
                            Text(
                                group,
                                Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
                                color = if (active) Color(0xFF67E8F9) else Color(0xFFCBD5E1),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            when (mode) { "Favorites" -> "Your favorites"; "History" -> "Recently watched"; else -> "All channels" },
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White
                        )
                        Text("${visibleChannels.size} channels available", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                    }
                    if (loading) CircularProgressIndicator(Modifier.size(19.dp), strokeWidth = 2.dp, color = Color(0xFF22D3EE))
                    TextButton(onClick = { scope.launch { refresh() } }) { Text("Refresh", color = Color(0xFF67E8F9)) }
                }
                error?.let {
                    Surface(color = Color(0xFF3B1720), shape = RoundedCornerShape(12.dp)) {
                        Text(it, Modifier.fillMaxWidth().padding(12.dp), color = Color(0xFFFDA4AF), style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (visibleChannels.isEmpty() && !loading) {
                    Surface(
                        Modifier.fillMaxWidth().weight(1f),
                        color = Color(0xFF101827),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Text(if (mode == "Favorites") "☆" else "⌕", color = Color(0xFF67E8F9), style = MaterialTheme.typography.displaySmall)
                            Text(if (mode == "Favorites") "No saved channels yet" else "No channels found", color = Color.White, style = MaterialTheme.typography.titleMedium)
                            Text(if (mode == "Favorites") "Tap the star on a channel to keep it here." else "Try another search or category.", color = Color(0xFF94A3B8), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                } else {
                    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                        columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(minSize = 270.dp),
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(bottom = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(visibleChannels, key = { it.url }) { channel ->
                            val active = selected?.url == channel.url
                            Surface(
                                modifier = Modifier.fillMaxWidth().clickable { play(channel) },
                                shape = RoundedCornerShape(16.dp),
                                color = if (active) Color(0xFF102A3A) else Color(0xFF111827),
                                border = androidx.compose.foundation.BorderStroke(
                                    if (active) 1.5.dp else 1.dp,
                                    if (active) Color(0xFF0891B2) else Color(0xFF253247)
                                ),
                                tonalElevation = if (active) 3.dp else 0.dp
                            ) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Surface(
                                            Modifier.size(58.dp),
                                            color = Color(0xFF080E19),
                                            shape = RoundedCornerShape(12.dp)
                                        ) {
                                            if (channel.logo.isNotBlank()) {
                                                AsyncImage(
                                                    model = channel.logo,
                                                    contentDescription = "${channel.name} logo",
                                                    modifier = Modifier.fillMaxSize().padding(6.dp),
                                                    contentScale = ContentScale.Fit
                                                )
                                            } else {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Image(painter = painterResource(R.drawable.ic_livetgtv), contentDescription = null, modifier = Modifier.size(38.dp))
                                                }
                                            }
                                        }
                                        Spacer(Modifier.width(11.dp))
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text(channel.name, color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                                            Text(channel.group, color = Color(0xFF94A3B8), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                        }
                                        IconButton(
                                            onClick = {
                                                val updated = favorites.toMutableSet()
                                                if (!updated.add(channel.url)) updated.remove(channel.url)
                                                favorites = updated
                                                prefs.edit().putStringSet("favorites", updated).apply()
                                            },
                                            modifier = Modifier.size(34.dp)
                                        ) {
                                            Text(if (favorites.contains(channel.url)) "★" else "☆", color = if (favorites.contains(channel.url)) Color(0xFFFBBF24) else Color(0xFF64748B), style = MaterialTheme.typography.titleLarge)
                                        }
                                    }
                                    androidx.compose.material3.HorizontalDivider(color = Color(0xFF263449))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (active) {
                                            Surface(color = Color(0xFF083344), shape = RoundedCornerShape(6.dp)) {
                                                Text("NOW PLAYING", Modifier.padding(horizontal = 7.dp, vertical = 4.dp), color = Color(0xFF67E8F9), style = MaterialTheme.typography.labelSmall)
                                            }
                                        } else {
                                            Text(if (!channel.licenseUrl.isNullOrBlank()) "DRM PROTECTED" else "LIVE CHANNEL", color = Color(0xFF64748B), style = MaterialTheme.typography.labelSmall)
                                        }
                                        Spacer(Modifier.weight(1f))
                                        Text(if (active) "Playing  ●" else "Watch  →", color = if (active) Color(0xFF67E8F9) else Color(0xFF94A3B8), style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showChannelDrawer) {
        Dialog(onDismissRequest = { showChannelDrawer = false }) {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().heightIn(max = 680.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Channel playlists", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        TextButton(onClick = { showChannelDrawer = false }) { Text("Close") }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Channels", "Favorites", "History").forEach { item ->
                            FilterChip(selected = mode == item, onClick = { mode = item; category = "All" }, label = { Text(item) })
                        }
                    }
                    OutlinedTextField(value = search, onValueChange = { search = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Search in playlist") }, singleLine = true)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (listOf("All") + channels.map { it.group }.distinct().sorted()).forEach { group ->
                            FilterChip(selected = category == group, onClick = { category = group }, label = { Text(group) })
                        }
                    }
                    Text("${visibleChannels.size} ${mode.lowercase()}", style = MaterialTheme.typography.labelMedium)
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(visibleChannels, key = { it.url }) { item ->
                            Surface(
                                modifier = Modifier.fillMaxWidth().clickable { play(item); showChannelDrawer = false },
                                shape = RoundedCornerShape(10.dp),
                                color = if (selected?.url == item.url) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    if (item.logo.isNotBlank()) AsyncImage(model = item.logo, contentDescription = null, modifier = Modifier.size(54.dp, 38.dp), contentScale = ContentScale.Fit)
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(item.name, style = MaterialTheme.typography.bodyMedium)
                                        Text(item.group, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (favorites.contains(item.url)) Text("★", color = MaterialTheme.colorScheme.primary)
                                    if (selected?.url == item.url) Text("PLAYING", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}


@OptIn(UnstableApi::class)
private fun showTrackDialog(
    context: android.content.Context,
    title: String,
    player: Player,
    trackType: Int
) {
    TrackSelectionDialogBuilder(context, title, player, trackType).build().show()
}
