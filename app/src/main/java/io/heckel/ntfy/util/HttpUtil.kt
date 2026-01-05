package io.heckel.ntfy.util

import android.content.Context
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Utility class for creating OkHttpClient instances with appropriate configurations.
 * All clients are configured with SSL/TLS settings from CertUtil for custom certificate support.
 */
object HttpUtil {
    /**
     * Client for regular API calls (auth, poll, etc.).
     */
    fun defaultClient(context: Context, baseUrl: String): OkHttpClient {
        return defaultBuilder(context, baseUrl).build()
    }

    /**
     * Client with a longer call timeout (5 minutes).
     * Allows for large file uploads or downloads.
     */
    fun longCallClient(context: Context, baseUrl: String): OkHttpClient {
        return defaultBuilder(context, baseUrl)
            .callTimeout(5, TimeUnit.MINUTES)
            .build()
    }

    /**
     * Client for long-polling/streaming subscriptions.
     */
    fun subscriberClient(context: Context, baseUrl: String): OkHttpClient {
        return emptyBuilder(context, baseUrl)
            .readTimeout(77, TimeUnit.SECONDS) // Long enough to allow for server-side keepalive messages
            .build()
    }

    /**
     * Client for WebSocket connections.
     * No read timeout, 1 minute ping interval, 10s connect timeout.
     */
    fun wsClient(context: Context, baseUrl: String): OkHttpClient {
        return emptyBuilder(context, baseUrl)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(1, TimeUnit.MINUTES) // Technically not necessary, the server also pings us
            .connectTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    fun defaultBuilder(context: Context, baseUrl: String): OkHttpClient.Builder {
        return emptyBuilder(context, baseUrl)
            .callTimeout(60, TimeUnit.SECONDS) // Increased to 60s (from 15s) to reduce client variance
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
    }

    fun emptyBuilder(context: Context, baseUrl: String): OkHttpClient.Builder {
        return CertUtil
            .getInstance(context)
            .withTLSConfig(OkHttpClient.Builder(), baseUrl)
    }
}

