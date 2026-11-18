package com.example.capillaflix

import android.app.Activity
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.*
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun PlaybackScreen(station: Station, onClose: () -> Unit, onChannelChange: ((Int) -> Unit)? = null) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var error by remember(station.id) { mutableStateOf<String?>(null) }
    var buffering by remember(station.id) { mutableStateOf(true) }
    var isPlaying by remember(station.id) { mutableStateOf(false) }
    var resume by remember { mutableStateOf(true) }
    val focus = remember { FocusRequester() }
    val currentChannelChange by rememberUpdatedState(onChannelChange)
    DisposableEffect(context, onChannelChange != null) {
        val activity = context as? MainActivity
        val handler: ((Int) -> Unit)? = if (onChannelChange == null) null else { step -> currentChannelChange?.invoke(step); Unit }
        activity?.onChannelStep = handler
        onDispose { if (activity?.onChannelStep === handler) activity?.onChannelStep = null }
    }
    var showChannelName by remember(station.id) { mutableStateOf(!station.radio) }
    LaunchedEffect(station.id) { delay(3_000); showChannelName = false }
    val player = remember(station.id) {
        val http = DefaultHttpDataSource.Factory().setUserAgent("Mozilla/5.0 CapillaFlix/1.0")
            .setConnectTimeoutMs(if (station.radio) 12000 else 30000)
            .setReadTimeoutMs(if (station.radio) 15000 else 30000)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(if (station.referer.isBlank()) emptyMap() else mapOf("Referer" to station.referer))
        ExoPlayer.Builder(context, DefaultRenderersFactory(context).setEnableDecoderFallback(true))
            .setMediaSourceFactory(DefaultMediaSourceFactory(http).apply {
                if (!station.radio) setLoadErrorHandlingPolicy(TvLoadErrorHandlingPolicy())
            })
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(15000, 45000, 1200, 2500).build())
            .build().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
                setHandleAudioBecomingNoisy(true)
                setMediaItem(MediaItem.Builder().setUri(station.url).apply {
                    if (station.url.contains(".m3u8")) setMimeType(MimeTypes.APPLICATION_M3U8)
                }.build())
                prepare(); playWhenReady = true
            }
    }
    DisposableEffect(player, owner) {
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) { buffering = state == Player.STATE_BUFFERING }
            override fun onIsPlayingChanged(value: Boolean) { isPlaying = value }
            override fun onPlayerError(e: PlaybackException) {
                error = when (e.errorCode) {
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                    PlaybackException.ERROR_CODE_DECODING_FAILED,
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> "El dispositivo no ha podido decodificar esta emisión. Prueba de nuevo o vuelve al menú."
                    else -> "No se puede conectar con ${station.name}. Comprueba la conexión o reinténtalo."
                }
            }
        }
        player.addListener(listener)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> { resume = player.playWhenReady; player.pause() }
                Lifecycle.Event.ON_RESUME -> if (resume) player.play()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); player.removeListener(listener); player.release(); window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    BackHandler { onClose() }
    LaunchedEffect(error, station.radio) { if (station.radio || error != null) focus.requestFocus() }
    Box(Modifier.fillMaxSize().background(Color(0xFF090D16))) {
        if (!station.radio && error == null) AndroidView(factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                keepScreenOn = true
                controllerAutoShow = true
                controllerShowTimeoutMs = 3500
                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                requestFocus()
                setOnKeyListener { _, code, event ->
                    if (event.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                        if (player.isPlaying) player.pause() else player.play(); true
                    } else false
                }
            }
        }, onRelease = { it.player = null }, modifier = Modifier.fillMaxSize())
        if (showChannelName && !station.radio) {
            Column(Modifier.align(Alignment.TopStart).padding(32.dp)
                .background(Color(0xE6101421)).padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text("${Catalog.tv.indexOfFirst { it.id == station.id } + 1}  ·  ${station.name}", color = Color(0xFFD9B777), fontSize = 20.sp)
                Text("P+ / P− Cambiar de canal", color = Color.LightGray, fontSize = 12.sp)
            }
        }
        if (!station.radio && error == null && buffering) {
            Text("Conectando con ${station.name}… Reintentando si es necesario",
                color = Color.White, fontSize = 14.sp,
                modifier = Modifier.align(Alignment.BottomStart).padding(32.dp)
                    .background(Color(0xCC090D16)).padding(horizontal = 16.dp, vertical = 10.dp))
        }
        if (station.radio || error != null) Column(Modifier.align(Alignment.Center).widthIn(max = 600.dp).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(if (station.radio) "♫  RADIO EN DIRECTO" else "EMISIÓN NO DISPONIBLE", color = Color(0xFFD9B777), fontSize = 17.sp)
            Text(station.name, color = Color.White, fontSize = 32.sp)
            Text(error ?: if (buffering) "Conectando con la emisora…" else if (isPlaying) "Escuchando en directo" else "En pausa", color = Color.LightGray)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(modifier = Modifier.focusRequester(focus), onClick = {
                    if (error != null) { error = null; player.prepare(); player.play() }
                    else if (player.isPlaying) player.pause() else player.play()
                }) { Text(if (error != null) "Reintentar" else if (isPlaying) "Pausar" else "Reproducir") }
                Button(onClick = onClose) { Text("Volver") }
            }
        }
    }
}
