package app.ferry

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/** One Ferry request on a TCP socket: a JSON header line, then `size` raw payload bytes. See README. */
class Wire(private val socket: Socket) : Closeable {
    private val input = BufferedInputStream(socket.getInputStream(), BUFFER)
    private val output = BufferedOutputStream(socket.getOutputStream(), BUFFER)

    val remoteHost: String? get() = socket.inetAddress?.hostAddress

    fun readHeader(): JSONObject {
        val line = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) throw IOException("The connection closed early.")
            if (b == '\n'.code) break
            line.write(b)
            if (line.size() > 64 * 1024) throw IOException("The request header is too long.")
        }
        return JSONObject(String(line.toByteArray(), Charsets.UTF_8))
    }

    fun writeHeader(header: JSONObject) {
        output.write((header.toString() + "\n").toByteArray(Charsets.UTF_8))
        output.flush()
    }

    /** Copies exactly [size] payload bytes into [sink]. */
    fun readPayload(size: Long, sink: OutputStream) {
        val chunk = ByteArray(BUFFER)
        var remaining = size
        while (remaining > 0) {
            val read = input.read(chunk, 0, minOf(chunk.size.toLong(), remaining).toInt())
            if (read == -1) throw IOException("The connection closed before the transfer finished.")
            sink.write(chunk, 0, read)
            remaining -= read
        }
    }

    fun writePayload(source: InputStream) {
        source.copyTo(output, BUFFER)
        output.flush()
    }

    fun writePayload(bytes: ByteArray) {
        output.write(bytes)
        output.flush()
    }

    override fun close() {
        runCatching { socket.close() }
    }

    companion object {
        const val SERVICE_TYPE = "_ferry._tcp"
        private const val BUFFER = 256 * 1024

        fun connect(host: String, port: Int, readTimeoutMs: Int = 30_000): Wire {
            val socket = Socket()
            socket.connect(InetSocketAddress(host, port), 5_000)
            socket.soTimeout = readTimeoutMs
            return Wire(socket)
        }
    }
}
