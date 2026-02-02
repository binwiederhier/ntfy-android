package io.heckel.ntfy.msg

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import io.heckel.ntfy.util.HttpUtil
import io.heckel.ntfy.util.Log
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Service for ntfy account API endpoints.
 * Used for login, logout, account sync, and subscription management.
 * See https://docs.ntfy.sh/publish/#account-api
 */
class AccountApiService(private val context: Context) {
    private val gson = Gson()

    /**
     * Login with username/password to get a bearer token.
     * POST /v1/account/token
     */
    suspend fun login(baseUrl: String, username: String, password: String): String {
        val url = accountTokenUrl(baseUrl)
        Log.d(TAG, "Logging in to $url as $username")
        val client = HttpUtil.defaultClient(context, baseUrl)
        val request = HttpUtil.requestBuilder(url)
            .post("".toRequestBody())
            .addHeader("Authorization", basicAuth(username, password))
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) {
                throw UnauthorizedException("Invalid username or password")
            } else if (!response.isSuccessful) {
                throw Exception("Login failed: ${response.code}")
            }
            val body = response.body.string()
            val tokenResponse = gson.fromJson(body, TokenResponse::class.java)
            if (tokenResponse.token.isNullOrEmpty()) {
                throw Exception("Login failed: No token in response")
            }
            Log.d(TAG, "Login successful for $username")
            return tokenResponse.token
        }
    }

    /**
     * Logout and invalidate the current token.
     * DELETE /v1/account/token
     */
    suspend fun logout(baseUrl: String, token: String) {
        val url = accountTokenUrl(baseUrl)
        Log.d(TAG, "Logging out from $url")
        val client = HttpUtil.defaultClient(context, baseUrl)
        val request = HttpUtil.requestBuilder(url)
            .delete()
            .addHeader("Authorization", bearerAuth(token))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Logout request failed: ${response.code}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Logout request failed", e)
        }
    }

    /**
     * Extend the current token's expiration.
     * PATCH /v1/account/token
     */
    suspend fun extendToken(baseUrl: String, token: String) {
        val url = accountTokenUrl(baseUrl)
        Log.d(TAG, "Extending token at $url")
        val client = HttpUtil.defaultClient(context, baseUrl)
        val request = HttpUtil.requestBuilder(url)
            .patch("".toRequestBody())
            .addHeader("Authorization", bearerAuth(token))
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) {
                throw UnauthorizedException("Token expired or invalid")
            } else if (!response.isSuccessful) {
                throw Exception("Failed to extend token: ${response.code}")
            }
            Log.d(TAG, "Token extended successfully")
        }
    }

    /**
     * Get account information including subscriptions.
     * GET /v1/account
     */
    suspend fun getAccount(baseUrl: String, token: String): AccountResponse {
        val url = accountUrl(baseUrl)
        Log.d(TAG, "Fetching account from $url")
        val client = HttpUtil.defaultClient(context, baseUrl)
        val request = HttpUtil.requestBuilder(url)
            .get()
            .addHeader("Authorization", bearerAuth(token))
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) {
                throw UnauthorizedException("Token expired or invalid")
            } else if (!response.isSuccessful) {
                throw Exception("Failed to get account: ${response.code}")
            }
            val body = response.body.string()
            Log.d(TAG, "Account response: $body")
            return gson.fromJson(body, AccountResponse::class.java)
        }
    }

    /**
     * Add a subscription to the user's account.
     * POST /v1/account/subscription
     */
    suspend fun addSubscription(baseUrl: String, token: String, subscriptionBaseUrl: String, topic: String): RemoteSubscription {
        val url = accountSubscriptionUrl(baseUrl)
        val payload = gson.toJson(AddSubscriptionRequest(subscriptionBaseUrl, topic))
        Log.d(TAG, "Adding subscription $topic at $subscriptionBaseUrl to $url")
        val client = HttpUtil.defaultClient(context, baseUrl)
        val request = HttpUtil.requestBuilder(url)
            .post(payload.toRequestBody())
            .addHeader("Authorization", bearerAuth(token))
            .addHeader("Content-Type", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) {
                throw UnauthorizedException("Token expired or invalid")
            } else if (!response.isSuccessful) {
                throw Exception("Failed to add subscription: ${response.code}")
            }
            val body = response.body.string()
            Log.d(TAG, "Add subscription response: $body")
            return gson.fromJson(body, RemoteSubscription::class.java)
        }
    }

    /**
     * Update a subscription in the user's account.
     * PATCH /v1/account/subscription
     */
    suspend fun updateSubscription(
        baseUrl: String,
        token: String,
        subscriptionBaseUrl: String,
        topic: String,
        displayName: String? = null
    ): RemoteSubscription {
        val url = accountSubscriptionUrl(baseUrl)
        val payload = gson.toJson(UpdateSubscriptionRequest(subscriptionBaseUrl, topic, displayName))
        Log.d(TAG, "Updating subscription $topic at $url")
        val client = HttpUtil.defaultClient(context, baseUrl)
        val request = HttpUtil.requestBuilder(url)
            .patch(payload.toRequestBody())
            .addHeader("Authorization", bearerAuth(token))
            .addHeader("Content-Type", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) {
                throw UnauthorizedException("Token expired or invalid")
            } else if (!response.isSuccessful) {
                throw Exception("Failed to update subscription: ${response.code}")
            }
            val body = response.body.string()
            Log.d(TAG, "Update subscription response: $body")
            return gson.fromJson(body, RemoteSubscription::class.java)
        }
    }

    /**
     * Delete a subscription from the user's account.
     * DELETE /v1/account/subscription
     */
    suspend fun deleteSubscription(baseUrl: String, token: String, subscriptionBaseUrl: String, topic: String) {
        val url = accountSubscriptionUrl(baseUrl)
        Log.d(TAG, "Deleting subscription $topic from $url")
        val client = HttpUtil.defaultClient(context, baseUrl)
        val request = HttpUtil.requestBuilder(url)
            .delete()
            .addHeader("Authorization", bearerAuth(token))
            .addHeader("X-BaseURL", subscriptionBaseUrl)
            .addHeader("X-Topic", topic)
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) {
                throw UnauthorizedException("Token expired or invalid")
            } else if (!response.isSuccessful) {
                throw Exception("Failed to delete subscription: ${response.code}")
            }
            Log.d(TAG, "Subscription deleted successfully")
        }
    }

    // URL helpers
    private fun accountUrl(baseUrl: String) = "$baseUrl/v1/account"
    private fun accountTokenUrl(baseUrl: String) = "$baseUrl/v1/account/token"
    private fun accountSubscriptionUrl(baseUrl: String) = "$baseUrl/v1/account/subscription"

    // Auth helpers
    private fun basicAuth(username: String, password: String): String {
        val credentials = "$username:$password"
        val encoded = android.util.Base64.encodeToString(credentials.toByteArray(), android.util.Base64.NO_WRAP)
        return "Basic $encoded"
    }

    private fun bearerAuth(token: String): String {
        return "Bearer $token"
    }

    // Request/Response data classes
    data class TokenResponse(
        val token: String?
    )

    data class AddSubscriptionRequest(
        @SerializedName("base_url") val baseUrl: String,
        val topic: String
    )

    data class UpdateSubscriptionRequest(
        @SerializedName("base_url") val baseUrl: String,
        val topic: String,
        @SerializedName("display_name") val displayName: String?
    )

    // Exception classes
    class UnauthorizedException(message: String) : Exception(message)

    companion object {
        private const val TAG = "NtfyAccountApiService"
    }
}

// Account response data classes (matching server JSON structure)
data class AccountResponse(
    val username: String?,
    val role: String?,
    val tier: TierInfo?,
    val limits: LimitsInfo?,
    val stats: StatsInfo?,
    val subscriptions: List<RemoteSubscription>?,
    val reservations: List<Reservation>?,
    val tokens: List<TokenInfo>?
)

data class TierInfo(
    val code: String?,
    val name: String?
)

data class LimitsInfo(
    val basis: String?,
    val messages: Long?,
    val emails: Long?,
    val calls: Long?,
    @SerializedName("reservations") val reservationsLimit: Int?,
    @SerializedName("attachment_total_size") val attachmentTotalSize: Long?,
    @SerializedName("attachment_file_size") val attachmentFileSize: Long?,
    @SerializedName("attachment_expiry") val attachmentExpiry: Long?,
    @SerializedName("attachment_bandwidth") val attachmentBandwidth: Long?
)

data class StatsInfo(
    val messages: Long?,
    @SerializedName("messages_remaining") val messagesRemaining: Long?,
    val emails: Long?,
    @SerializedName("emails_remaining") val emailsRemaining: Long?,
    val calls: Long?,
    @SerializedName("calls_remaining") val callsRemaining: Long?,
    val reservations: Int?,
    @SerializedName("reservations_remaining") val reservationsRemaining: Int?,
    @SerializedName("attachment_total_size") val attachmentTotalSize: Long?,
    @SerializedName("attachment_total_size_remaining") val attachmentTotalSizeRemaining: Long?
)

data class RemoteSubscription(
    val id: String?,
    @SerializedName("base_url") val baseUrl: String,
    val topic: String,
    @SerializedName("display_name") val displayName: String?
)

data class Reservation(
    val topic: String,
    val everyone: String?
)

data class TokenInfo(
    val token: String?,
    val label: String?,
    val last_access: Long?,
    val last_origin: String?,
    val expires: Long?
)
