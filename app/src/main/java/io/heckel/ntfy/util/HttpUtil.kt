package io.heckel.ntfy.util

import android.content.Context
import android.os.Build
import io.heckel.ntfy.BuildConfig
import io.heckel.ntfy.db.CustomHeader
import io.heckel.ntfy.db.User
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.TimeUnit

/**
 * Utility class for creating OkHttpClient instances and Request builders.
 * All clients are configured with SSL/TLS settings from CertUtil for custom certificate support.
 */
object HttpUtil {
    val USER_AGENT = "ntfy/${BuildConfig.VERSION_NAME} (${BuildConfig.FLAVOR}; Android ${Build.VERSION.RELEASE}; SDK ${Build.VERSION.SDK_INT})"

    /**
     * Client for regular API calls (auth, poll, etc.).
     */
    suspend fun defaultClient(context: Context, baseUrl: String): OkHttpClient {
        return defaultClientBuilder(context, baseUrl).build()
    }

    /**
     * Client with a longer call timeout (5 minutes).
     * Allows for large file uploads or downloads.
     */
    suspend fun longCallClient(context: Context, baseUrl: String): OkHttpClient {
        return defaultClientBuilder(context, baseUrl)
            .callTimeout(5, TimeUnit.MINUTES)
            .build()
    }

    /**
     * Client for long-polling/streaming subscriptions.
     */
    suspend fun subscriberClient(context: Context, baseUrl: String): OkHttpClient {
        return emptyClientBuilder(context, baseUrl)
            .readTimeout(77, TimeUnit.SECONDS) // Long enough to allow for server-side keepalive messages
            .build()
    }

    /**
     * Client for WebSocket connections.
     * No read timeout, 1 minute ping interval, 10s connect timeout.
     */
    suspend fun wsClient(context: Context, baseUrl: String): OkHttpClient {
        return emptyClientBuilder(context, baseUrl)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(1, TimeUnit.MINUTES) // Technically not necessary, the server also pings us
            .connectTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    fun requestBuilder(url: String, user: User? = null, customHeaders: List<CustomHeader> = emptyList()): Request.Builder {
        val builder = Request.Builder()
            .url(url)
            .addHeader("User-Agent", USER_AGENT)
        if (user != null) {
            builder.addHeader("Authorization", Credentials.basic(user.username, user.password, UTF_8))
        }
        customHeaders.forEach { header ->
            builder.addHeader(header.name, header.value)
        }
        return builder
    }

    private suspend fun emptyClientBuilder(context: Context, baseUrl: String): OkHttpClient.Builder {
        return CertUtil
            .getInstance(context)
            .withTLSConfig(OkHttpClient.Builder(), baseUrl)
    }

    private suspend fun defaultClientBuilder(context: Context, baseUrl: String): OkHttpClient.Builder {
        return emptyClientBuilder(context, baseUrl)
            .callTimeout(1, TimeUnit.MINUTES) // Increased to 1min (from 15s) to reduce client variance
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
    }
}

