package io.heckel.ntfy.account

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.util.HttpUtil
import io.heckel.ntfy.util.Log
import okhttp3.Credentials
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Client for the ntfy account API (/v1/account/...), the same endpoints the web app uses.
 * See https://docs.ntfy.sh/subscribe/web/#accounts for what the account holds.
 */
class AccountApi(private val context: Context) {
    private val repository = Repository.getInstance(context)
    private val gson = Gson()

    /**
     * Creates a non-expiring token for this device, so the session survives the phone being offline
     * for a while. It shows up (and can be revoked) under Account > Access tokens in the web app.
     */
    suspend fun login(baseUrl: String, username: String, password: String): String {
        val body = gson.toJson(TokenRequest(label = TOKEN_LABEL, expires = 0))
        val request = requestBuilder(baseUrl, ACCOUNT_TOKEN_PATH, Credentials.basic(username, password, Charsets.UTF_8))
            .post(body.toRequestBody())
            .build()
        execute(baseUrl, request).use { response ->
            val token = gson.fromJson(response.body.string(), TokenResponse::class.java)?.token
            if (token.isNullOrEmpty()) {
                throw Exception("No token in response")
            }
            return token
        }
    }

    suspend fun logout(baseUrl: String, token: String) {
        val request = requestBuilder(baseUrl, ACCOUNT_TOKEN_PATH, bearer(token))
            .delete()
            .build()
        execute(baseUrl, request).close()
    }

    suspend fun subscriptions(baseUrl: String, token: String): List<RemoteSubscription> {
        val request = requestBuilder(baseUrl, ACCOUNT_PATH, bearer(token))
            .get()
            .build()
        execute(baseUrl, request).use { response ->
            val account = gson.fromJson(response.body.string(), AccountResponse::class.java)
            return account?.subscriptions ?: emptyList()
        }
    }

    suspend fun addSubscription(baseUrl: String, token: String, subscription: RemoteSubscription) {
        val request = requestBuilder(baseUrl, ACCOUNT_SUBSCRIPTION_PATH, bearer(token))
            .post(gson.toJson(subscription).toRequestBody())
            .build()
        execute(baseUrl, request, allowConflict = true).close() // 409 means it's already there
    }

    suspend fun updateSubscription(baseUrl: String, token: String, subscription: RemoteSubscription) {
        val request = requestBuilder(baseUrl, ACCOUNT_SUBSCRIPTION_PATH, bearer(token))
            .patch(gson.toJson(subscription).toRequestBody())
            .build()
        execute(baseUrl, request).close()
    }

    suspend fun deleteSubscription(baseUrl: String, token: String, subscriptionBaseUrl: String, topic: String) {
        // DELETE has no body, so the server reads the subscription from headers
        val request = requestBuilder(baseUrl, ACCOUNT_SUBSCRIPTION_PATH, bearer(token))
            .addHeader("X-BaseURL", subscriptionBaseUrl)
            .addHeader("X-Topic", topic)
            .delete()
            .build()
        execute(baseUrl, request).close()
    }

    private suspend fun requestBuilder(baseUrl: String, path: String, authorization: String): Request.Builder {
        // Custom headers may be needed to reach the server (e.g. behind a proxy), but we bring our own auth
        val customHeaders = repository.getCustomHeaders(baseUrl)
            .filterNot { it.name.equals("Authorization", ignoreCase = true) }
        return HttpUtil.requestBuilder("$baseUrl$path", user = null, customHeaders)
            .addHeader("Authorization", authorization)
    }

    private suspend fun execute(baseUrl: String, request: Request, allowConflict: Boolean = false): Response {
        Log.d(TAG, "${request.method} ${request.url}")
        val response = HttpUtil.defaultClient(context, baseUrl).newCall(request).execute()
        if (response.code == 401 || response.code == 403) {
            response.close()
            throw UnauthorizedException()
        } else if (!response.isSuccessful && !(allowConflict && response.code == 409)) {
            response.close()
            throw Exception("Unexpected response ${response.code} from ${request.method} ${request.url}")
        }
        return response
    }

    private fun bearer(token: String): String {
        return "Bearer $token"
    }

    class UnauthorizedException : Exception("Invalid username, password or token")

    private data class TokenRequest(val label: String, val expires: Long)
    private data class TokenResponse(val token: String?)
    private data class AccountResponse(val subscriptions: List<RemoteSubscription>?)

    data class RemoteSubscription(
        @SerializedName("base_url") val baseUrl: String,
        val topic: String,
        @SerializedName("display_name") val displayName: String?
    )

    companion object {
        private const val TAG = "NtfyAccountApi"
        private const val TOKEN_LABEL = "ntfy Android"
        private const val ACCOUNT_PATH = "/v1/account"
        private const val ACCOUNT_TOKEN_PATH = "/v1/account/token"
        private const val ACCOUNT_SUBSCRIPTION_PATH = "/v1/account/subscription"
    }
}
