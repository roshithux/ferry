package app.ferry

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket

/** Keeps Ferry reachable: listens for the Mac, advertises over Bonjour and watches for Macs. */
class FerryService : Service() {
    private var server: ServerSocket? = null
    private var discovery: Discovery? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var statusJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        ServiceCompat.startForeground(
            this, Notifications.SERVICE_ID,
            Notifications.service(this, Ferry.paired.value?.name, online = false),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )

        // Android drops multicast packets without this, which hides Bonjour services.
        multicastLock = getSystemService(WifiManager::class.java).createMulticastLock("ferry").apply {
            setReferenceCounted(false)
            acquire()
        }

        val socket = openServer()
        server = socket
        Ferry.serverPort = socket.localPort
        Ferry.scope.launch {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (e: IOException) { break }
                Ferry.scope.launch { Inbox.handle(client) }
            }
        }

        discovery = Discovery(this, socket.localPort).also { it.start() }

        val notifications = getSystemService(NotificationManager::class.java)
        statusJob = Ferry.scope.launch {
            combine(Ferry.paired, Ferry.macs) { mac, found -> mac?.name to Ferry.isMacOnline(mac, found) }
                .distinctUntilChanged()
                .collect { (name, online) ->
                    notifications.notify(Notifications.SERVICE_ID, Notifications.service(this@FerryService, name, online))
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        statusJob?.cancel()
        discovery?.stop()
        runCatching { server?.close() }
        multicastLock?.release()
        Ferry.serverPort = 0
        super.onDestroy()
    }

    /** Prefers the fixed port so the Mac's remembered address keeps working; Bonjour carries whichever we get. */
    private fun openServer(): ServerSocket = try {
        ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(PORT))
        }
    } catch (e: IOException) {
        ServerSocket(0)
    }

    private companion object {
        const val PORT = 47821
    }
}
