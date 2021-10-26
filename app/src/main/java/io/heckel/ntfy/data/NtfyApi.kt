package io.heckel.ntfy.data

import android.content.Context
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class Event(val name: String = "", val data: JsonObject = JsonObject())

class NtfyApi(context: Context) {

}
