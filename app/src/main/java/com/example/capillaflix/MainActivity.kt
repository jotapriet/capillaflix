package com.example.capillaflix

import android.content.ActivityNotFoundException
import android.content.Intent
import android.annotation.SuppressLint
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.*
import coil.compose.AsyncImage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

private val Gold = Color(0xFFD9B777)
private val Muted = Color(0xFFA4ADBE)

class MainActivity : ComponentActivity() {
    internal var onChannelStep: ((Int) -> Unit)? = null

    // Public Android window callback; AndroidX Core marks its inherited implementation restricted.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val step = when (event.keyCode) {
            KeyEvent.KEYCODE_CHANNEL_UP -> 1
            KeyEvent.KEYCODE_CHANNEL_DOWN -> -1
            else -> return super.dispatchKeyEvent(event)
        }
        val handler = onChannelStep ?: return super.dispatchKeyEvent(event)
        // Consume the whole press, but change only once (not on release or long-press repeats).
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) handler(step)
        return true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent { MaterialTheme { CapillaApp() } }
    }
}

data class FeedUi(val videos: List<Video> = emptyList(), val loading: Boolean = false, val message: String = "")
class HomeModel(app: android.app.Application) : AndroidViewModel(app) {
    private val repository = FeedRepository(app)
    private val mutable = MutableStateFlow(FeedUi(repository.cached()))
    val state = mutable.asStateFlow()
    private var job: Job? = null
    val radios = Catalog.radios(app)
    init { refresh() }
    fun refresh() {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            mutable.value = mutable.value.copy(loading = true)
            try {
                val result = repository.refresh()
                mutable.value = FeedUi(result.videos, message = when {
                    result.failed == Catalog.handles.size -> "Sin conexión con YouTube · Reintentar con Actualizar"
                    result.failed > 0 || result.liveFailed > 0 -> "Feed parcial · Algunos canales o directos no se pudieron consultar"
                    result.videos.isEmpty() -> "No hay vídeos disponibles"
                    else -> "Vídeos recientes · Directos primero"
                })
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.value = mutable.value.copy(loading = false, message = "No se pudo actualizar · Reinténtalo") }
        }
    }
}

@Composable
private fun CapillaApp(model: HomeModel = viewModel(), updates: UpdateModel = viewModel()) {
    val feed by model.state.collectAsStateWithLifecycle()
    val updateUi by updates.state.collectAsStateWithLifecycle()
    var showUpdates by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf<Station?>(null) }
    var focusId by remember { mutableStateOf("ptv") }
    val rows = List(3) { rememberLazyListState() }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    LaunchedEffect(lifecycle) {
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            model.refresh()
            updates.check(automatic = true)
            while (true) { delay(120_000); model.refresh(); updates.check(automatic = true) }
        }
    }
    if (showUpdates) {
        UpdateScreen(updates, onClose = { showUpdates = false })
        return
    }
    if (playing != null) {
        val station = playing!!
        key(station.id) {
            PlaybackScreen(station, onClose = { playing = null },
                onChannelChange = if (station.radio) null else { step ->
                    adjacentTelevision(playing?.id, step)?.let { next ->
                        focusId = next.id
                        playing = next
                    }
                })
        }
        return
    }
    val restore = remember { FocusRequester() }
    val restoreId = focusId.takeIf { id -> id == "refresh" || id == "updates" || Catalog.tv.any { it.id == id } || model.radios.any { it.id == id } || feed.videos.any { it.id == id } } ?: "ptv"
    LaunchedEffect(Unit) { delay(100); runCatching { restore.requestFocus() } }
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF151A29), Color(0xFF090D16)))).padding(horizontal = 32.dp, vertical = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.ic_brand), contentDescription = null, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(8.dp))
            Text("CAPILLA", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text("FLIX", fontSize = 23.sp, fontWeight = FontWeight.Light, color = Gold)
            Text("   SEVILLA · EN CASA", fontSize = 11.sp, color = Muted, modifier = Modifier.weight(1f))
            Button(onClick = { focusId = "updates"; showUpdates = true },
                modifier = if (restoreId == "updates") Modifier.focusRequester(restore) else Modifier) {
                Text(if (updateUi.release != null) "Nueva versión" else "Actualizaciones", fontSize = 11.sp)
            }
            Spacer(Modifier.width(10.dp))
            Button(onClick = { model.refresh() }, enabled = !feed.loading,
                modifier = if (restoreId == "refresh") Modifier.focusRequester(restore) else Modifier) {
                Text(if (feed.loading) "Actualizando…" else "Actualizar", fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        SectionHeader("01", "EN DIRECTO", "Televisión local", null)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val width = (maxWidth - 36.dp) / 4
            LazyRow(state = rows[0], horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
                itemsIndexed(Catalog.tv, key = { _, s -> s.id }) { i, station ->
                    Tile(station.name, "EN DIRECTO", width, Modifier.fillParentMaxHeight().then(if (restoreId == station.id) Modifier.focusRequester(restore) else Modifier),
                        label = listOf("PTV", "M+V", "7TV", "101")[i], onClick = { focusId = station.id; playing = station })
                }
            }
        }
        Spacer(Modifier.height(7.dp))
        SectionHeader("02", "A LA CARTA", if (feed.loading) "Cargando YouTube…" else feed.message, "YouTube ↗")
        BoxWithConstraints(Modifier.weight(1.35f).fillMaxWidth()) {
            val width = (maxWidth - 36.dp) / 4
            if (feed.videos.isEmpty()) {
                Button(onClick = { focusId = "refresh"; model.refresh() }, modifier = Modifier.align(Alignment.Center)) {
                    Text(if (feed.loading) "Consultando los 14 canales…" else "Reintentar carga de YouTube")
                }
            } else LazyRow(state = rows[1], horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
                itemsIndexed(feed.videos, key = { _, v -> v.id }) { _, video ->
                    Tile(video.title, if (video.live) "● EN DIRECTO · ${video.channel}" else "${video.channel} · ${java.time.Instant.ofEpochMilli(video.published).atZone(java.time.ZoneId.systemDefault()).toLocalDate()}",
                        width, Modifier.fillParentMaxHeight().then(if (restoreId == video.id) Modifier.focusRequester(restore) else Modifier), image = video.thumbnail,
                        onClick = { focusId = video.id; openYoutube(context, video.id) })
                }
            }
        }
        Spacer(Modifier.height(7.dp))
        SectionHeader("03", "RADIO", "Sevilla y tus emisoras cofrades", "${model.radios.size} emisoras")
        BoxWithConstraints(Modifier.weight(0.72f).fillMaxWidth()) {
            val width = (maxWidth - 48.dp) / 5
            LazyRow(state = rows[2], horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
                itemsIndexed(model.radios, key = { _, s -> s.id }) { _, station ->
                    Tile(station.name, "♫  ESCUCHAR", width, Modifier.fillParentMaxHeight().then(if (restoreId == station.id) Modifier.focusRequester(restore) else Modifier),
                        onClick = { focusId = station.id; playing = station })
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("↑ ↓ Cambiar de fila     ← → Explorar     OK Abrir     Atrás Volver", color = Muted, fontSize = 10.sp, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(16.dp))
            Text("Made by @jotapriet (GitHub)", color = Muted, fontSize = 10.sp)
        }
    }
}

@Composable
private fun SectionHeader(number: String, title: String, subtitle: String, end: String?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(number, color = Gold, fontSize = 10.sp)
        Text("  $title", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text("   $subtitle", color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (end != null) Text(end, color = Gold, fontSize = 10.sp)
    }
}

@Composable
private fun Tile(title: String, subtitle: String, width: androidx.compose.ui.unit.Dp, modifier: Modifier, image: String? = null, label: String? = null, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = modifier.width(width),
        colors = CardDefaults.colors(containerColor = Color(0xFF1D2537), focusedContainerColor = Color(0xFF35415A)),
        scale = CardDefaults.scale(focusedScale = 1.025f)) {
        Box(Modifier.fillMaxSize()) {
            if (image != null) {
                AsyncImage(image, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF0090D16)))))
            }
            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.Bottom) {
                if (label != null) Text(label, color = Gold, fontSize = 27.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 16.sp)
                Text(subtitle, color = if (subtitle.startsWith("●")) Gold else Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 9.sp, lineHeight = 12.sp)
            }
        }
    }
}

private fun openYoutube(context: android.content.Context, id: String) {
    val url = "https://www.youtube.com/watch?v=$id".toUri()
    try { context.startActivity(Intent(Intent.ACTION_VIEW, url).setPackage("com.google.android.youtube.tv")) }
    catch (_: ActivityNotFoundException) {
        try { context.startActivity(Intent(Intent.ACTION_VIEW, url)) }
        catch (_: ActivityNotFoundException) { Toast.makeText(context, "Instala YouTube para abrir este vídeo", Toast.LENGTH_LONG).show() }
    }
}

