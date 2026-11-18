package com.example.capillaflix

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal const val MAX_UPDATE_BYTES = 150L * 1024 * 1024

internal data class AppRelease(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val minSdk: Int,
    val notes: String
)

internal fun requireHttpsUrl(value: String): String {
    val url = value.trim().toHttpUrlOrNull()
    require(url != null && url.isHttps && url.username.isEmpty() && url.password.isEmpty()) {
        "El origen debe ser una URL HTTPS válida, sin usuario ni contraseña."
    }
    return url.toString()
}

internal fun parseAppRelease(json: String): AppRelease {
    val data = JSONObject(json)
    val versionCode = data.getLong("versionCode")
    val versionName = data.getString("versionName").trim()
    val sha = data.getString("sha256").lowercase()
    val minSdk = data.getInt("minSdk")
    require(versionCode > 0 && versionName.isNotEmpty() && versionName.length <= 80 && minSdk >= 26) { "La información de versión no es válida." }
    require(Regex("[a-f0-9]{64}").matches(sha)) { "Falta una huella SHA-256 válida del APK." }
    return AppRelease(versionCode, versionName, requireHttpsUrl(data.getString("apkUrl")), sha, minSdk,
        data.optString("notes").take(2_000))
}

internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
private fun ByteArray.toHex() = joinToString("") { "%02x".format(it.toInt() and 255) }

internal class UpdateTransport(
    private val directory: File,
    private val client: OkHttpClient,
    private val verifyApk: (File, AppRelease) -> Unit
) {
    init { directory.mkdirs() }

    suspend fun check(url: String): AppRelease = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(requireHttpsUrl(url)).header("Cache-Control", "no-cache")
            .header("User-Agent", "CapillaFlix/${BuildConfig.VERSION_NAME}").build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "El servidor de actualizaciones responde HTTP ${response.code}." }
            val body = response.body ?: error("El servidor no ha devuelto información de versión.")
            // Bound the manifest read, including responses without Content-Length.
            val bytes = body.byteStream().readNBytesCompat(64 * 1024 + 1)
            check(bytes.size <= 64 * 1024) { "El archivo de actualización es demasiado grande." }
            parseAppRelease(bytes.toString(Charsets.UTF_8))
        }
    }

    suspend fun download(release: AppRelease, progress: (Int?) -> Unit): File = suspendCancellableCoroutine { continuation ->
        // A single download runs at a time in UpdateModel. Completed files live in private cache.
        val downloadId = java.util.UUID.randomUUID().toString()
        val temporary = File(directory, "$downloadId.part")
        val destination = File(directory, "$downloadId.apk")
        val call = client.newCall(Request.Builder().url(requireHttpsUrl(release.apkUrl))
            .header("User-Agent", "CapillaFlix/${BuildConfig.VERSION_NAME}").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                temporary.delete()
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        check(it.isSuccessful) { "No se puede descargar el APK: HTTP ${it.code}." }
                        val body = it.body ?: error("La descarga está vacía.")
                        val length = body.contentLength()
                        check(length <= MAX_UPDATE_BYTES) { "El APK supera el tamaño permitido (150 MB)." }
                        val digest = MessageDigest.getInstance("SHA-256")
                        var downloaded = 0L
                        var previous = -1
                        progress(if (length > 0) 0 else null)
                        body.byteStream().use { input -> temporary.outputStream().use { output ->
                            val buffer = ByteArray(32 * 1024)
                            while (true) {
                                if (!continuation.isActive) throw IOException("Descarga cancelada")
                                val count = input.read(buffer)
                                if (count == -1) break
                                downloaded += count
                                check(downloaded <= MAX_UPDATE_BYTES) { "El APK supera el tamaño permitido (150 MB)." }
                                digest.update(buffer, 0, count)
                                output.write(buffer, 0, count)
                                if (length > 0) {
                                    val percent = ((downloaded * 100) / length).coerceAtMost(100).toInt()
                                    if (percent != previous) { progress(percent); previous = percent }
                                }
                            }
                        } }
                        check(downloaded > 0 && (length < 0 || downloaded == length)) { "La descarga está incompleta." }
                        check(digest.digest().toHex() == release.sha256) { "El APK no coincide con la huella SHA-256 publicada. Vuelve a descargarlo." }
                        verifyApk(temporary, release)
                        check(continuation.isActive) { "Descarga cancelada" }
                        check(temporary.renameTo(destination)) { "No se puede guardar el APK descargado." }
                    }
                    if (continuation.isActive) continuation.resume(destination)
                } catch (e: Exception) {
                    temporary.delete()
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }
        })
    }

}

internal class AppUpdateRepository(private val context: Context) {
    private val directory = File(context.cacheDir, "updates").apply {
        mkdirs()
        // Leftovers from a previous process are never offered for installation.
        listFiles()?.filter { it.isFile }?.forEach { it.delete() }
    }
    private val transport = UpdateTransport(directory,
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .followSslRedirects(false).build(), ::validateApk)

    suspend fun check(url: String): AppRelease = transport.check(url)
    suspend fun download(release: AppRelease, progress: (Int?) -> Unit): File = transport.download(release, progress)

    @Suppress("DEPRECATION")
    fun validateApk(file: File, release: AppRelease) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: error("El archivo descargado no es un APK válido.")
        val installed = pm.getPackageInfo(context.packageName, flags)
        check(archive.packageName == context.packageName) { "El APK pertenece a otra aplicación." }
        check(PackageInfoCompat.getLongVersionCode(archive) == release.versionCode &&
            release.versionCode > PackageInfoCompat.getLongVersionCode(installed)) { "La versión del APK no corresponde a una actualización de esta app." }
        check((archive.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE) <= Build.VERSION.SDK_INT && release.minSdk <= Build.VERSION.SDK_INT) {
            "Esta actualización necesita una versión de Android más reciente."
        }
        val trusted = signerHashes(installed)
        check(trusted.isNotEmpty() && signerHashes(archive) == trusted) {
            "El APK no está firmado con la misma clave que la aplicación instalada."
        }
    }

    @Suppress("DEPRECATION")
    private fun signerHashes(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return signatures.orEmpty().map { sha256(it.toByteArray()) }.toSet()
    }
}

private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    while (output.size() < limit) {
        val count = read(buffer, 0, minOf(buffer.size, limit - output.size()))
        if (count == -1) break
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
