package io.legado.app.lib.cronet

import androidx.annotation.Keep
import okhttp3.RequestBody
import okio.BufferedSource
import okio.Pipe
import okio.buffer
import org.chromium.net.UploadDataProvider
import org.chromium.net.UploadDataSink
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService

/**
 * 用于上传大型文件
 *
 * @property body
 * @property executorService
 */
@Keep
class LargeBodyUploadProvider(
    private val body: RequestBody,
    private val executorService: ExecutorService
) : UploadDataProvider(), AutoCloseable {
    private var pipe = Pipe(BUFFER_SIZE.toLong())
    private var source: BufferedSource = pipe.source.buffer()

    @Volatile
    private var filled: Boolean = false
    override fun getLength(): Long {
        return body.contentLength()
    }

    override fun read(uploadDataSink: UploadDataSink, byteBuffer: ByteBuffer) {
        if (!filled) {
            fillBuffer()
        }
        check(byteBuffer.hasRemaining()) { "Cronet passed a buffer with no bytes remaining" }
        var bytesRead = 0
        while (bytesRead == 0) {
            val read = source.read(byteBuffer)
            if (read < 0) {
                uploadDataSink.onReadSucceeded(true)
                return
            }
            bytesRead += read
        }
        uploadDataSink.onReadSucceeded(false)
    }

    @Synchronized
    private fun fillBuffer() {
        executorService.submit {
            try {
                pipe.sink.buffer().use { writeSink ->
                    filled = true
                    body.writeTo(writeSink)
                    writeSink.flush()
                }
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }

    }

    override fun rewind(uploadDataSink: UploadDataSink) {
        check(!body.isOneShot()) { "OkHttp RequestBody is oneShot" }
        filled = false
        runCatching { source.close() }
        pipe = Pipe(BUFFER_SIZE.toLong())
        source = pipe.source.buffer()
        fillBuffer()
        uploadDataSink.onRewindSucceeded()
    }

    override fun close() {
        runCatching { source.close() }
        super.close()
    }
}
