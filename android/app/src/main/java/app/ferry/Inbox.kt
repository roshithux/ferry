package app.ferry

import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Socket

/** Handles one incoming request from the paired Mac. */
object Inbox {
    private const val TAG = "Ferry"
    private const val MAX_TEXT = 10L * 1024 * 1024

    fun handle(socket: Socket) {
        socket.soTimeout = 30_000
        Wire(socket).use { wire ->
            try {
                serve(wire)
            } catch (e: Exception) {
                Log.w(TAG, "Incoming transfer failed", e)
            }
        }
    }

    private fun serve(wire: Wire) {
        val header = wire.readHeader()
        val mac = Ferry.paired.value
        if (mac == null || header.optString("id") != mac.id || header.optString("token") != mac.token) {
            wire.writeHeader(failure("This Mac isn't paired with the phone. Pair again from the phone."))
            return
        }
        Ferry.rememberMacAddress(wire.remoteHost, header.optInt("port"))
        val size = header.optLong("size")

        when (header.optString("type")) {
            "text" -> {
                if (size > MAX_TEXT) {
                    wire.writeHeader(failure("That text is too long to send."))
                    return
                }
                val buffer = ByteArrayOutputStream()
                wire.readPayload(size, buffer)
                Ferry.receivedText(String(buffer.toByteArray(), Charsets.UTF_8), mac)
            }
            "file" -> {
                val name = header.optString("name").substringAfterLast('/').ifBlank { "Ferry file" }
                val uri = saveToDownloads(wire, name, size)
                Ferry.receivedFile(uri, name, mac, toClipboard = header.optBoolean("clip"))
            }
            else -> {
                wire.writeHeader(failure("The phone doesn't understand that request. Update Ferry."))
                return
            }
        }
        wire.writeHeader(JSONObject().put("ok", true))
    }

    /** Streams the payload into Download/Ferry. MediaStore renames it if the name is taken. */
    private fun saveToDownloads(wire: Wire, name: String, size: Long): Uri {
        val extension = name.substringAfterLast('.', "").lowercase()
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Ferry")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = Ferry.app.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Couldn't create the file in Downloads.")
        try {
            (resolver.openOutputStream(uri) ?: throw IOException("Couldn't write to Downloads.")).use { wire.readPayload(size, it) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    private fun failure(message: String) = JSONObject().put("ok", false).put("error", message)
}
