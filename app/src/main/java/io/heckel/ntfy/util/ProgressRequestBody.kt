package io.heckel.ntfy.util

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer

/**
 * A RequestBody wrapper that reports upload progress.
 */
class ProgressRequestBody(
    private val delegate: RequestBody,
    private val onProgress: (bytesWritten: Long, totalBytes: Long) -> Unit
) : RequestBody() {
    
    override fun contentType(): MediaType? = delegate.contentType()
    
    override fun contentLength(): Long = delegate.contentLength()
    
    override fun writeTo(sink: BufferedSink) {
        val totalBytes = contentLength()
        val countingSink = object : ForwardingSink(sink) {
            var bytesWritten = 0L
            
            override fun write(source: Buffer, byteCount: Long) {
                super.write(source, byteCount)
                bytesWritten += byteCount
                onProgress(bytesWritten, totalBytes)
            }
        }
        val bufferedSink = countingSink.buffer()
        delegate.writeTo(bufferedSink)
        bufferedSink.flush()
    }
}

