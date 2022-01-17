package io.heckel.ntfy.msg

import androidx.annotation.Keep

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
    val title: String?,
    val message: String,
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

const val MESSAGE_ENCODING_BASE64 = "base64"
