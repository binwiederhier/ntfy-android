package io.heckel.ntfy.util

import io.heckel.ntfy.db.Repository
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object CustomHeaderConfig {

    fun getCustomHeaders(repository: Repository): Map<String, String> {
        return repository.getCustomHeaders()
    }

    fun addCustomHeader(repository: Repository, name: String, value: String) {
        val headers = repository.getCustomHeaders().toMutableMap()
        headers[name] = value
        repository.setCustomHeaders(headers)
    }

    fun removeCustomHeader(repository: Repository, name: String) {
        val headers = repository.getCustomHeaders().toMutableMap()
        headers.remove(name)
        repository.setCustomHeaders(headers)
    }
}
