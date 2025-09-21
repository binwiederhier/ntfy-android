package io.heckel.ntfy.util

import io.heckel.ntfy.db.Repository

object CustomHeaderConfig {
    // Default header names - users can configure the values in settings
    const val CUSTOM_HEADER_1_NAME = "CF-Access-Client-Id"
    const val CUSTOM_HEADER_2_NAME = "CF-Access-Client-Secret"

    fun getCustomHeaders(repository: Repository): Map<String, String> {
        val headers = mutableMapOf<String, String>()

        val clientId = repository.getCustomHeader1()
        val clientSecret = repository.getCustomHeader2()

        if (clientId.isNotEmpty()) {
            headers[CUSTOM_HEADER_1_NAME] = clientId
        }

        if (clientSecret.isNotEmpty()) {
            headers[CUSTOM_HEADER_2_NAME] = clientSecret
        }

        return headers
    }
}