package com.example.capillaflix

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit

class AppUpdatesTest {
    @get:Rule val files = TemporaryFolder()

    private val payload = "signed-apk-test-fixture".toByteArray()
    private fun metadata(url: String = "https://example.com/app.apk") = JSONObject()
        .put("versionCode", 3).put("versionName", "1.2").put("apkUrl", url)
        .put("sha256", sha256(payload)).put("minSdk", 26).put("notes", "Novedades")

    @Test fun readsVersionAndIntegrityData() {
        val release = parseAppRelease(metadata().toString())
        assertEquals(3L, release.versionCode)
        assertEquals("1.2", release.versionName)
        assertEquals(sha256(payload), release.sha256)
    }

    @Test fun rejectsInsecureSourcesAndInvalidMetadata() {
        for (url in listOf("http://example.com/app.apk", "https://user:password@example.com/app.apk", "not-a-url")) {
            assertThrows(IllegalArgumentException::class.java) { parseAppRelease(metadata(url).toString()) }
        }
        assertThrows(IllegalArgumentException::class.java) { parseAppRelease(metadata().put("sha256", "invalid").toString()) }
        assertThrows(IllegalArgumentException::class.java) { parseAppRelease(metadata().put("versionCode", 0).toString()) }
    }

    private fun withServer(test: (MockWebServer, OkHttpClient) -> Unit) {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName("127.0.0.1").addSubjectAlternativeName("::1").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        MockWebServer().use { server ->
            server.useHttps(serverTls.sslSocketFactory(), false)
            server.start()
            val client = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
                .followSslRedirects(false).readTimeout(3, TimeUnit.SECONDS).build()
            try { test(server, client) }
            finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
        }
    }

    @Test fun downloadsOverHttpsThenChecksIntegrityBeforeApkValidation() = withServer { server, client ->
        val dir = files.newFolder()
        var validated = false
        val transport = UpdateTransport(dir, client) { apk, release ->
            assertArrayEquals(payload, apk.readBytes())
            assertEquals(3L, release.versionCode)
            validated = true
        }
        val url = server.url("/app.apk").toString()
        server.enqueue(MockResponse().setBody(metadata(url).toString()))
        server.enqueue(MockResponse().setBody(String(payload)))
        runBlocking {
            val release = transport.check(server.url("/latest.json").toString())
            val progress = mutableListOf<Int?>()
            val apk = transport.download(release) { progress += it }
            assertTrue(validated)
            assertTrue(apk.exists())
            assertEquals(100, progress.last())
            assertFalse(dir.listFiles().orEmpty().any { it.extension == "part" })
        }
    }

    @Test fun corruptedDownloadsAreRemovedWithoutCallingInstallerValidation() = withServer { server, client ->
        val dir = files.newFolder()
        val transport = UpdateTransport(dir, client) { _, _ -> fail("Invalid hash must never pass verification") }
        server.enqueue(MockResponse().setBody("corrupted"))
        runBlocking {
            try { transport.download(parseAppRelease(metadata(server.url("/app.apk").toString()).toString())) {}; fail("Expected checksum failure") }
            catch (e: IllegalStateException) { assertTrue(e.message.orEmpty().contains("SHA-256")) }
        }
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    @Test fun incorrectSignerOrPackageCannotProduceAnInstallableFile() = withServer { server, client ->
        val dir = files.newFolder()
        val transport = UpdateTransport(dir, client) { _, _ -> error("Firma incorrecta") }
        server.enqueue(MockResponse().setBody(String(payload)))
        runBlocking {
            try { transport.download(parseAppRelease(metadata(server.url("/app.apk").toString()).toString())) {}; fail("Expected signature failure") }
            catch (e: IllegalStateException) { assertEquals("Firma incorrecta", e.message) }
        }
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    @Test fun cancellationClosesConnectionAndRemovesPartialFile() = withServer { server, client ->
        val dir = files.newFolder()
        val transport = UpdateTransport(dir, client) { _, _ -> fail("Cancelled APK must not be verified") }
        server.enqueue(MockResponse().setBody("x".repeat(10_000)).throttleBody(1, 1, TimeUnit.SECONDS))
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val task = launch {
                transport.download(parseAppRelease(metadata(server.url("/app.apk").toString()).toString())) { started.complete(Unit) }
            }
            withTimeout(5_000) { started.await() }
            task.cancelAndJoin()
            withTimeout(5_000) { while (dir.listFiles().orEmpty().isNotEmpty()) delay(20) }
        }
    }
}
