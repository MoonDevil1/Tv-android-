package com.moondevil1.livetgtv
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

private const val PLAYLIST_URL="https://livetgtv.lovable.app/api/public/playlist.m3u"
private val WIDEVINE_UUID=UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed")
class MainActivity:ComponentActivity(){
 @OptIn(UnstableApi::class) override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContent{LiveTvScreen()}}
}
@OptIn(UnstableApi::class)
@Composable private fun LiveTvScreen(){
 val context=LocalContext.current;val scope=rememberCoroutineScope()
 var channels by remember{mutableStateOf<List<Channel>>(emptyList())};var selected by remember{mutableStateOf<Channel?>(null)}
 var search by remember{mutableStateOf("")};var category by remember{mutableStateOf("All Channels")}
 var error by remember{mutableStateOf<String?>(null)};var loading by remember{mutableStateOf(true)}
 val player=remember{ExoPlayer.Builder(context).build()}
 suspend fun refresh(){
  loading=true;error=null
  try{val text=withContext(Dispatchers.IO){val c=URL(PLAYLIST_URL).openConnection() as HttpURLConnection;c.connectTimeout=15000;c.readTimeout=20000;try{c.inputStream.bufferedReader().use{it.readText()}}finally{c.disconnect()}}
   channels=PlaylistParser.parse(text);if(channels.isEmpty())error="Playlist returned no channels."
  }catch(e:Exception){error=e.message?:"Could not load playlist."}finally{loading=false}
 }
 fun play(channel:Channel){
  selected=channel
  try{
   val mediaItem=MediaItem.Builder().setUri(channel.url).apply{
    if(!channel.licenseUrl.isNullOrBlank())setDrmConfiguration(MediaItem.DrmConfiguration.Builder(WIDEVINE_UUID).setLicenseUri(channel.licenseUrl!!).setLicenseRequestHeaders(channel.headers).build())
   }.build()
   val dataSourceFactory=DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true).setDefaultRequestProperties(channel.headers)
   player.setMediaSource(DefaultMediaSourceFactory(dataSourceFactory).createMediaSource(mediaItem));player.prepare();player.playWhenReady=true;error=null
  }catch(e:Exception){error=e.message?:"Playback could not start."}
 }
 LaunchedEffect(Unit){refresh()};DisposableEffect(Unit){onDispose{player.release()}}
 MaterialTheme(colorScheme=darkColorScheme(primary=androidx.compose.ui.graphics.Color(0xFF00DCCB),background=androidx.compose.ui.graphics.Color(0xFF080B16),surface=androidx.compose.ui.graphics.Color(0xFF111827))){
  Column(Modifier.fillMaxSize().padding(12.dp)){
   Text("LiveTGTV",style=MaterialTheme.typography.headlineMedium,color=MaterialTheme.colorScheme.primary)
   Text("LIVE CHANNELS",style=MaterialTheme.typography.labelSmall)
   selected?.let{ch->AndroidView(Modifier.fillMaxWidth().aspectRatio(16f/9f),factory={ctx->PlayerView(ctx).apply{this.player=player;useController=true}},update{it.player=player});Text(ch.name,style=MaterialTheme.typography.titleMedium)}
   OutlinedTextField(search,{search=it},Modifier.fillMaxWidth(),label={Text("Search channels")},singleLine=true)
   val groups=listOf("All Channels")+channels.map{it.group}.distinct().sorted()
   ScrollableTabRow(selectedTabIndex=groups.indexOf(category).coerceAtLeast(0),edgePadding=0.dp){groups.forEach{g->Tab(category==g,{category=g},text={Text(g)})}}
   Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("${channels.size} channels");TextButton(onClick={scope.launch{refresh()}}){Text("Refresh")}}
   if(loading)LinearProgressIndicator(Modifier.fillMaxWidth())
   error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
   LazyColumn(Modifier.weight(1f)){items(channels.filter{(category=="All Channels"||it.group==category)&&it.name.contains(search,true)}){ch->
    ListItem(headlineContent={Text(ch.name)},supportingContent={Text(ch.group+if(ch.licenseUrl!=null)" · DRM" else "")},modifier=Modifier.fillMaxWidth().clickable{play(ch)})
    HorizontalDivider()
   }}
  }
 }
}
