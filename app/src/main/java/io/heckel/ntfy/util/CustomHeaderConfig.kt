package io.heckel.ntfy.util

object CustomHeaderConfig {
    // Set these to your Cloudflare Zero Trust values
    const val CUSTOM_HEADER_1_NAME = "CF-Access-Client-Id"
    const val CUSTOM_HEADER_1_VALUE = "your-client-id-here"

    const val CUSTOM_HEADER_2_NAME = "CF-Access-Client-Secret"
    const val CUSTOM_HEADER_2_VALUE = "your-client-secret-here"

    // Enable/disable custom headers easily
    const val CUSTOM_HEADERS_ENABLED = true

    fun getCustomHeaders(): Map<String, String> {
        return if (CUSTOM_HEADERS_ENABLED) {
            mapOf(
                CUSTOM_HEADER_1_NAME to CUSTOM_HEADER_1_VALUE,
                CUSTOM_HEADER_2_NAME to CUSTOM_HEADER_2_VALUE
            )
        } else {
            emptyMap()
        }
    }
}