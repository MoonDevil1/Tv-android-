package com.moondevil1.livetgtv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

private const val PLAYLIST_URL = "https://livetgtv.lovable.app/api/public/playlist.m3u"
private val WIDEVINE_UUID = UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed")

class MainActivity : ComponentActivity() {
    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LiveTvScreen() }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun LiveTvScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var channels by remember { mutableStateOf<List<Channel>>(emptyList()) }
    var selected by remember { mutableStateOf<Channel?>(null) }
    var search by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("All Channels") }
    var error by remember { mutableStateOf<String?>(null) }
    var playbackError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    val player = remember { ExoPlayer.Builder(context).build() }
    val playerListener = remember {
        object : Player.Listener {
            override fun onPlayerError(exception: PlaybackException) {
                playbackError = "Playback failed (${exception.errorCodeName}): ${exception.message ?: "The stream could not be opened."}"
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
                try {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } finally {
                    connection.disconnect()
                }
            }
            channels = PlaylistParser.parse(text)
            if (channels.isEmpty()) error = "Playlist returned no channels."
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
        try {
            val streamUrl = channel.url.lowercase()
            val mimeType = when {
                channel.manifestType?.contains("mpd", true) == true ||
                    channel.manifestType?.contains("dash", true) == true ||
                    streamUrl.substringBefore('?').endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
                channel.manifestType?.contains("hls", true) == true ||
                    streamUrl.substringBefore('?').endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
                else -> null
            }
            val mediaItem = MediaItem.Builder().setUri(channel.url).apply {
                if (mimeType != null) setMimeType(mimeType)
                if (!channel.licenseUrl.isNullOrBlank()) {
                    setDrmConfiguration(
                        MediaItem.DrmConfiguration.Builder(WIDEVINE_UUID)
                            .setLicenseUri(channel.licenseUrl!!)
                            .setLicenseRequestHeaders(channel.headers)
                            .build()
                    )
                }
            }.build()
            val dataSourceFactory = DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setDefaultRequestProperties(channel.headers)
            player.stop()
            player.setMediaSource(DefaultMediaSourceFactory(dataSourceFactory).createMediaSource(mediaItem))
            player.prepare()
            player.playWhenReady = true
        } catch (exception: Exception) {
            playbackError = exception.message ?: "Playback could not start."
        }
    }

    LaunchedEffect(Unit) { refresh() }
    DisposableEffect(player, playerListener) {
        player.addListener(playerListener)
        onDispose {
            player.removeListener(playerListener)
            player.release()
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = androidx.compose.ui.graphics.Color(0xFF00DCCB),
            background = androidx.compose.ui.graphics.Color(0xFF080B16),
            surface = androidx.compose.ui.graphics.Color(0xFF111827)
        )
    ) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Image(
                    painter = painterResource(R.drawable.ic_livetgtv),
                    contentDescription = "LiveTGTV app logo",
                    modifier = Modifier.size(48.dp),
                    contentScale = ContentScale.Fit
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "LiveTGTV",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text("LIVE CHANNELS", style = MaterialTheme.typography.labelSmall)

            selected?.let { channel ->
                AndroidView(
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                    factory = { viewContext ->
                        PlayerView(viewContext).apply {
                            this.player = player
                            useController = true
                            keepScreenOn = true
                        }
                    },
                    update = { it.player = player }
                )
                Text(channel.name, style = MaterialTheme.typography.titleMedium)
            }

            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Search channels") },
                singleLine = true
            )
            val groups = listOf("All Channels") + channels.map { it.group }.distinct().sorted()
            ScrollableTabRow(
                selectedTabIndex = groups.indexOf(category).coerceAtLeast(0),
                edgePadding = 0.dp
            ) {
                groups.forEach { group ->
                    Tab(selected = category == group, onClick = { category = group }, text = { Text(group) })
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${channels.size} channels")
                TextButton(onClick = { scope.launch { refresh() } }) { Text("Refresh") }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            playbackError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            LazyColumn(Modifier.weight(1f)) {
                items(channels.filter {
                    (category == "All Channels" || it.group == category) && it.name.contains(search, true)
                }) { channel ->
                    ListItem(
                        leadingContent = {
                            if (channel.logo.isNotBlank()) {
                                AsyncImage(
                                    model = channel.logo,
                                    contentDescription = "${channel.name} logo",
                                    modifier = Modifier.size(width = 76.dp, height = 48.dp),
                                    contentScale = ContentScale.Fit
                                )
                            } else {
                                Image(
                                    painter = painterResource(R.drawable.ic_livetgtv),
                                    contentDescription = "Channel logo unavailable",
                                    modifier = Modifier.size(44.dp),
                                    contentScale = ContentScale.Fit
                                )
                            }
                        },
                        headlineContent = { Text(channel.name) },
                        supportingContent = {
                            Text(channel.group + if (channel.licenseUrl != null) " · DRM" else "")
                        },
                        modifier = Modifier.fillMaxWidth().clickable { play(channel) }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
