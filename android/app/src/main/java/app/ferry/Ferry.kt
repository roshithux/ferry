package app.ferry

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** A Mac advertising Ferry on this Wi-Fi. */
data class MacService(val id: String, val name: String, val host: String, val port: Int)

data class Activity(
    val incoming: Boolean,
    val title: String,
    val detail: String,
    val uri: Uri? = null,
    val time: Long = System.currentTimeMillis(),
    /** Full text for clipboard items, so tapping one can copy it again. */
    val text: String? = null,
)

/** App-wide state and the send/receive operations shared by the service, activities and tile. */
object Ferry {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val macs = MutableStateFlow<Map<String, MacService>>(emptyMap())
    val paired = MutableStateFlow<PairedMac?>(null)
    val activity = MutableStateFlow<List<Activity>>(emptyList())
    val busy = MutableStateFlow<String?>(null)

    @Volatile var serverPort = 0
    @Volatile var discovery: Discovery? = null
    lateinit var app: Context
        private set

    private val sendLock = Mutex()
    private val main = Handler(Looper.getMainLooper())

    val deviceName: String
        get() = Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL

    fun init(context: Context) {
        app = context.applicationContext
        Store.init(app)
        paired.value = Store.mac
        activity.value = Store.activity
    }

    fun startService(context: Context) {
        ContextCompat.startForegroundService(context, Intent(context, FerryService::class.java))
    }

    fun isMacOnline(mac: PairedMac?, found: Map<String, MacService>) = mac != null && found.containsKey(mac.id)

    // Pairing

    suspend fun pair(mac: MacService, code: String) = withContext(Dispatchers.IO) {
        // The Mac waits for its user to click Allow, so give the reply plenty of time.
        Wire.connect(mac.host, mac.port, readTimeoutMs = 120_000).use { wire ->
            wire.writeHeader(
                JSONObject().put("v", 1).put("type", "pair").put("id", Store.deviceId)
                    .put("name", deviceName).put("port", serverPort).put("code", code)
            )
            val reply = wire.readHeader()
            if (!reply.optBoolean("ok")) throw IOException(reply.optString("error", "Pairing was declined on the Mac."))
            val paired = PairedMac(reply.getString("id"), reply.getString("name"), reply.getString("token"), mac.host, mac.port)
            Store.mac = paired
            this@Ferry.paired.value = paired
            record(Activity(false, "Paired with ${paired.name}", ""))
        }
    }

    fun unpair() {
        Store.mac = null
        paired.value = null
        // Show every Mac on the Wi-Fi right away, in case the running search has gone quiet.
        discovery?.refresh()
    }

    fun rememberMacAddress(host: String?, port: Int) {
        val current = paired.value ?: return
        if (host == null) return
        val updated = current.copy(host = host, port = if (port > 0) port else current.port)
        if (updated != current) {
            Store.mac = updated
            paired.value = updated
        }
    }

    // Sending

    suspend fun sendText(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        send("Sending clipboard…", "text", bytes.size.toLong(), { it.put("clip", true) }) { it.writePayload(bytes) }
        record(Activity(false, "Clipboard to ${paired.value?.name ?: "Mac"}", preview(text), text = text))
    }

    /**
     * Reads the clipboard (call while an activity has focus; Android blocks it otherwise)
     * and sends its text or image in the background.
     */
    fun sendClipboard(context: Context) {
        val mac = paired.value ?: return toast("Open Ferry and pair with your Mac first.")
        val item = context.getSystemService(ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
        val image = item?.uri?.takeIf { context.contentResolver.getType(it)?.startsWith("image/") == true }
        val text = item?.text?.toString()
        when {
            image != null -> sendInBackground("Image sent to ${mac.name}") { sendUri(image, clip = true) }
            !text.isNullOrEmpty() -> sendInBackground("Clipboard sent to ${mac.name}") { sendText(text) }
            else -> toast("The clipboard is empty. Copy some text or an image first.")
        }
    }

    suspend fun sendUri(uri: Uri, clip: Boolean = false) {
        val resolver = app.contentResolver
        var name = "Shared file"
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(0)?.let { name = it }
                if (!cursor.isNull(1)) size = cursor.getLong(1)
            }
        }
        // The protocol needs the size up front; some providers don't report it, so copy those first.
        var temp: File? = null
        if (size < 0) {
            temp = File.createTempFile("ferry", null, app.cacheDir)
            withContext(Dispatchers.IO) {
                (resolver.openInputStream(uri) ?: throw IOException("Couldn't open $name.")).use { input ->
                    temp.outputStream().use { input.copyTo(it) }
                }
            }
            size = temp.length()
        }
        try {
            send("Sending $name…", "file", size, { it.put("name", name).put("clip", clip) }) { wire ->
                val input = temp?.inputStream() ?: resolver.openInputStream(uri) ?: throw IOException("Couldn't open $name.")
                input.use { wire.writePayload(it) }
            }
        } finally {
            temp?.delete()
        }
        record(Activity(false, name, "To ${paired.value?.name ?: "Mac"}", uri))
    }

    /** Runs [block] on [scope] and reports the outcome as a toast. */
    fun sendInBackground(success: String, block: suspend () -> Unit) = scope.launch {
        try {
            block()
            toast(success)
        } catch (e: Exception) {
            toast(e.message ?: "Couldn't send. Please try again.")
        }
    }

    private suspend fun send(
        label: String,
        type: String,
        size: Long,
        extra: (JSONObject) -> Unit,
        body: (Wire) -> Unit,
    ) = sendLock.withLock {
        val mac = paired.value ?: throw IOException("Pair with your Mac first.")
        busy.value = label
        try {
            withContext(Dispatchers.IO) {
                connect(mac).use { wire ->
                    val header = JSONObject().put("v", 1).put("type", type).put("id", Store.deviceId)
                        .put("token", mac.token).put("port", serverPort).put("size", size)
                    extra(header)
                    wire.writeHeader(header)
                    body(wire)
                    val reply = wire.readHeader()
                    if (!reply.optBoolean("ok")) throw IOException(reply.optString("error", "${mac.name} refused the transfer."))
                }
            }
        } finally {
            busy.value = null
        }
    }

    private fun connect(mac: PairedMac): Wire {
        val candidates = buildList {
            macs.value[mac.id]?.let { add(it.host to it.port) }
            mac.host?.let { add(it to mac.port) }
        }.distinct()
        var lastError: Exception? = null
        for ((host, port) in candidates) {
            try {
                return Wire.connect(host, port).also { rememberMacAddress(host, port) }
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw IOException("Couldn't reach ${mac.name}. Check that Ferry is running on the Mac and both are on the same Wi-Fi.", lastError)
    }

    // Receiving (called by Inbox)

    fun receivedText(text: String, mac: PairedMac) {
        main.post {
            app.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("From ${mac.name}", text))
        }
        record(Activity(true, "Clipboard from ${mac.name}", preview(text), text = text))
        toast("Copied from ${mac.name}")
    }

    /** [toClipboard] is set for images the Mac sent from its clipboard, such as screenshots. */
    fun receivedFile(uri: Uri, name: String, mac: PairedMac, toClipboard: Boolean) {
        if (toClipboard) {
            main.post {
                app.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newUri(app.contentResolver, "Image from ${mac.name}", uri))
            }
            record(Activity(true, "Image from ${mac.name}", "On the clipboard · Download/Ferry", uri))
            toast("Image copied from ${mac.name}")
            return
        }
        record(Activity(true, name, "From ${mac.name} · Download/Ferry", uri))
        Notifications.fileReceived(app, uri, name, mac.name)
    }

    /** What tapping a Recent item does: opens the file, or copies the text again. */
    fun open(context: Context, item: Activity) {
        val uri = item.uri
        if (uri != null) {
            // Received files can be deleted since, and shared-in files may no longer be readable.
            val exists = try {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { it.moveToFirst() } == true
            } catch (e: SecurityException) {
                return toast("Ferry can no longer open ${item.title}. Find it in the app you shared it from.")
            } catch (e: Exception) {
                false
            }
            if (!exists) return toast("${item.title} is no longer on this phone.")
            val view = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: "*/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try {
                context.startActivity(view)
            } catch (e: ActivityNotFoundException) {
                toast("No app on this phone can open ${item.title}.")
            } catch (e: SecurityException) {
                toast("Ferry can no longer open ${item.title}. Find it in the app you shared it from.")
            }
        } else if (item.text != null) {
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Ferry", item.text))
            toast("Copied")
        }
    }

    // Helpers

    private fun record(item: Activity) {
        Store.activity = activity.updateAndGet { (listOf(item) + it).take(MAX_RECENT) }
    }

    fun toast(message: String) {
        main.post { Toast.makeText(app, message, Toast.LENGTH_SHORT).show() }
    }

    private const val MAX_RECENT = 20

    private fun preview(text: String): String {
        val line = text.lineSequence().firstOrNull().orEmpty()
        return if (line.length > 60) line.take(60) + "…" else line
    }
}
