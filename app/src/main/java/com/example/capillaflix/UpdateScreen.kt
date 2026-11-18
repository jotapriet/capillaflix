package com.example.capillaflix

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.Text

@Composable
internal fun UpdateScreen(model: UpdateModel, onClose: () -> Unit) {
    val ui by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var editing by remember { mutableStateOf(ui.sourceUrl.isBlank()) }
    var source by remember(ui.sourceUrl) { mutableStateOf(ui.sourceUrl) }
    val first = remember { FocusRequester() }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (context.packageManager.canRequestPackageInstalls()) model.install(context)
        else model.message("No se ha concedido permiso para instalar. Puedes volver a intentarlo con Instalar.")
    }
    fun close() { model.cancelDownload(); onClose() }
    BackHandler { if (editing && ui.sourceUrl.isNotBlank()) editing = false else close() }
    LaunchedEffect(Unit) { first.requestFocus() }
    Column(Modifier.fillMaxSize().background(Color(0xFF101421)).padding(horizontal = 40.dp, vertical = 24.dp)
        .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("ACTUALIZACIONES", color = Color(0xFFD9B777), fontSize = 23.sp)
                Text("CapillaFlix ${BuildConfig.VERSION_NAME} · Versión ${BuildConfig.VERSION_CODE}", color = Color.LightGray, fontSize = 13.sp)
            }
            Button(onClick = { close() }, modifier = Modifier.focusRequester(first)) { Text("Volver") }
        }
        Text(ui.message.ifBlank { "Configura el origen cuando hayas preparado tu repositorio de GitHub." }, color = Color.White, fontSize = 16.sp)
        if (editing) {
            Text("URL HTTPS del archivo latest.json", color = Color.LightGray, fontSize = 13.sp)
            BasicTextField(value = source, onValueChange = { source = it }, singleLine = true,
                enabled = !ui.busy, textStyle = TextStyle(color = Color.White, fontSize = 15.sp), cursorBrush = SolidColor(Color(0xFFD9B777)),
                modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFFD9B777)).padding(14.dp))
            Text("Puedes dejarla vacía para desactivar las comprobaciones automáticas.", color = Color.LightGray, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { if (model.saveSource(source)) editing = false }, enabled = !ui.busy) { Text("Guardar origen") }
                Button(onClick = { source = ui.sourceUrl; editing = false }, enabled = !ui.busy) { Text("Cancelar") }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { model.check() }, enabled = !ui.busy && ui.sourceUrl.isNotBlank()) { Text("Buscar actualizaciones") }
                Button(onClick = { editing = true }, enabled = !ui.busy) { Text("Configurar origen") }
            }
        }
        ui.release?.let { release ->
            Text("Nueva versión: ${release.versionName}", color = Color(0xFFD9B777), fontSize = 20.sp)
            if (release.notes.isNotBlank()) Text(release.notes, color = Color.White, fontSize = 14.sp, maxLines = 5)
            if (ui.downloading) {
                Text(ui.progress?.let { "Descargando: $it %" } ?: "Descargando…", color = Color.White)
                Button(onClick = { model.cancelDownload() }) { Text("Cancelar descarga") }
            } else if (ui.apk != null) {
                Button(onClick = {
                    if (context.packageManager.canRequestPackageInstalls()) model.install(context)
                    else {
                        model.message("Autoriza a CapillaFlix para instalar aplicaciones en los ajustes de Android.")
                        try { permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())) }
                        catch (_: Exception) { model.message("Abre Ajustes de Android > Seguridad > Instalar aplicaciones desconocidas y autoriza a CapillaFlix.") }
                    }
                }, enabled = !ui.busy) { Text("Instalar actualización") }
            } else {
                Button(onClick = { model.download() }, enabled = !ui.busy && release.minSdk <= Build.VERSION.SDK_INT) { Text("Descargar actualización") }
            }
        }
        Text("La descarga requiere tu acción. Android te pedirá confirmar la instalación.", color = Color.LightGray, fontSize = 12.sp)
    }
}
