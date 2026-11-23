package com.moeez.carnav

import okhttp3.Call
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Request
import org.conscrypt.Conscrypt
import java.io.IOException
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * HTTPS for CarNav's own requests (search + routing).
 *
 * Why not HttpURLConnection: the head unit runs Android 8.1, whose built-in
 * TLS stops at TLS 1.2, and the OSRM routing server (routing.openstreetmap.de,
 * alias router.project-osrm.org) accepts TLS 1.3 only — every route request
 * failed with "SSL handshake aborted" (found on the real unit, 2026-11-23).
 * Conscrypt (Google's TLS library, the one inside newer Android) brings
 * TLS 1.3; OkHttp uses it. Certificates are still checked against Android's
 * own trusted root store — nothing about verification is relaxed.
 * Map tiles still go through osmdroid's own loader (their server allows TLS 1.2).
 */
object Http {
    private val trustManager: X509TrustManager by lazy {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?) // the system's trusted roots
        tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    val client: OkHttpClient by lazy {
        val tls = SSLContext.getInstance("TLS", Conscrypt.newProvider())
        tls.init(null, arrayOf(trustManager), null)
        OkHttpClient.Builder()
            .sslSocketFactory(tls.socketFactory, trustManager)
            .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS)) // TLS 1.3 + 1.2 only
            .retryOnConnectionFailure(false) // retries are NavModel's job (bounded, backed off)
            .build()
    }

    class Response(val code: Int, val body: String)

    /** A GET that can be cancelled from another thread with [Call.cancel]. */
    fun newCall(url: String, connectMs: Int, readMs: Int): Call =
        client.newBuilder()
            .connectTimeout(connectMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(readMs.toLong(), TimeUnit.MILLISECONDS)
            .build()
            .newCall(Request.Builder().url(url).header("User-Agent", Api.USER_AGENT).build())

    @Throws(IOException::class)
    fun execute(call: Call): Response = call.execute().use { r ->
        Response(r.code, r.body?.string().orEmpty())
    }
}
