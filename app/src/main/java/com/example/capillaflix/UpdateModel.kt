package com.example.capillaflix

import android.app.Application
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

internal data class UpdateUi(
    val sourceUrl: String = "",
    val release: AppRelease? = null,
    val busy: Boolean = false,
    val downloading: Boolean = false,
    val progress: Int? = null,
    val apk: File? = null,
    val message: String = ""
)

internal class UpdateModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
    private val repository = AppUpdateRepository(app)
    private val mutable = MutableStateFlow(UpdateUi(sourceUrl = prefs.getString("source", BuildConfig.UPDATE_MANIFEST_URL).orEmpty()))
    val state = mutable.asStateFlow()
    private var work: Job? = null
    private val downloadGeneration = java.util.concurrent.atomic.AtomicInteger()

    init { check(automatic = true) }

    fun saveSource(url: String): Boolean {
        if (mutable.value.busy) return false
        val source = try { if (url.isBlank()) "" else requireHttpsUrl(url) }
        catch (e: Exception) { message(e.message.orEmpty()); return false }
        mutable.value.apk?.delete()
        prefs.edit().putString("source", source).remove("last_check").apply()
        mutable.value = UpdateUi(sourceUrl = source, message = if (source.isEmpty()) "Las comprobaciones automáticas están desactivadas." else "Origen guardado.")
        if (source.isNotBlank()) check()
        return true
    }

    fun check(automatic: Boolean = false) {
        if (work?.isActive == true) return
        val source = mutable.value.sourceUrl
        if (source.isBlank()) {
            if (!automatic) message("Configura la URL de actualizaciones para consultar nuevas versiones.")
            return
        }
        val now = System.currentTimeMillis()
        if (automatic && now - prefs.getLong("last_check", 0) in 0 until 6 * 60 * 60 * 1000L) return
        prefs.edit().putLong("last_check", now).apply()
        work = viewModelScope.launch {
            mutable.value = mutable.value.copy(busy = true, message = "Buscando actualizaciones…")
            try {
                val release = repository.check(source)
                val available = release.versionCode > BuildConfig.VERSION_CODE
                val previous = mutable.value
                val keepApk = if (previous.release == release) previous.apk else null
                if (keepApk == null) previous.apk?.delete()
                mutable.value = previous.copy(busy = false, release = release.takeIf { available }, apk = keepApk,
                    message = when {
                        !available -> "Tienes la última versión disponible."
                        release.minSdk > Build.VERSION.SDK_INT -> "Hay una nueva versión, pero necesita Android API ${release.minSdk} o superior."
                        else -> "Disponible CapillaFlix ${release.versionName}."
                    })
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.value = mutable.value.copy(busy = false, message = e.message ?: "No se pudo consultar el servidor.") }
        }
    }

    fun download() {
        if (work?.isActive == true) return
        val release = mutable.value.release ?: return
        if (release.minSdk > Build.VERSION.SDK_INT) return
        mutable.value.apk?.delete()
        val generation = downloadGeneration.incrementAndGet()
        work = viewModelScope.launch {
            mutable.value = mutable.value.copy(busy = true, downloading = true, progress = null, apk = null, message = "Descargando actualización…")
            try {
                val apk = repository.download(release) { progress ->
                    if (downloadGeneration.get() == generation) mutable.value = mutable.value.copy(progress = progress)
                }
                mutable.value = mutable.value.copy(busy = false, downloading = false, progress = 100, apk = apk,
                    message = "Descarga verificada. Ya puedes instalar la actualización.")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                mutable.value = mutable.value.copy(busy = false, downloading = false, progress = null, message = e.message ?: "No se pudo descargar la actualización.")
            }
        }
    }

    fun cancelDownload() {
        if (!mutable.value.downloading) return
        downloadGeneration.incrementAndGet()
        work?.cancel()
        mutable.value = mutable.value.copy(busy = false, downloading = false, progress = null, message = "Descarga cancelada.")
    }

    fun message(value: String) { mutable.value = mutable.value.copy(message = value) }

    fun install(context: Context) {
        val apk = mutable.value.apk ?: return
        val release = mutable.value.release ?: return
        try {
            repository.validateApk(apk, release)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
            context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newRawUri("CapillaFlix", uri)
            })
            message("Confirma la actualización en el instalador de Android. Si la cancelas, puedes volver a pulsar Instalar.")
        } catch (e: Exception) {
            message("No se pudo abrir el instalador: ${e.message.orEmpty()}")
        }
    }
}
