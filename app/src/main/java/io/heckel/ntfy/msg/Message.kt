package io.heckel.ntfy.msg

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

/* This annotation ensures that proguard still works in production builds,
 * see https://stackoverflow.com/a/62753300/1440785 */
@Keep
data class Message(
    val id: String,
    val time: Long,
    val event: String,
    val topic: String,
    val priority: Int?,
    val tags: List<String>?,
    val click: String?,
    val icon: String?,
    val actions: List<MessageAction>?,
    val title: String?,
    val message: String,
    @SerializedName("content_type") val contentType: String?,
    val encoding: String?,
    val attachment: MessageAttachment?,
)

@Keep
data class MessageAttachment(
    val name: String,
    val type: String?,
    val size: Long?,
    val expires: Long?,
    val url: String,
)

@Keep
data class MessageAction(
    val id: String,
    val action: String,
    val label: String, // "view", "broadcast" or "http"
    val clear: Boolean?, // clear notification after successful execution
    val url: String?, // used in "view" and "http" actions
    val method: String?, // used in "http" action, default is POST (!)
    val headers: Map<String,String>?, // used in "http" action
    val body: String?, // used in "http" action
    val intent: String?, // used in "broadcast" action
    val extras: Map<String,String>?, // used in "broadcast" action
)

const val MESSAGE_ENCODING_BASE64 = "base64"
