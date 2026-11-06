package app.ferry

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicBoolean

/** Advertises this phone over Bonjour and keeps [Ferry.macs] in sync with the Macs on the network. */
class Discovery(context: Context, private val port: Int) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private val idsByServiceName = mutableMapOf<String, String>()
    private val attempts = mutableMapOf<String, Int>()
    /** Services currently announced, so a Mac that has left isn't resolved or retried again. */
    private val visible = mutableSetOf<String>()
    /** Bumped by [refresh], so callbacks from the previous search are ignored. */
    private var generation = 0
    private var browse: Browse? = null
    private val handler = Handler(Looper.getMainLooper())

    private val registration = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(info: NsdServiceInfo) = Unit
        override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
        override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) { Log.w(TAG, "Advertising failed: $error") }
        override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) = Unit
    }

    private inner class Browse : NsdManager.DiscoveryListener {
        private val isCurrent get() = synchronized(this@Discovery) { browse === this }

        override fun onDiscoveryStarted(serviceType: String) = Unit
        override fun onDiscoveryStopped(serviceType: String) = Unit
        override fun onStartDiscoveryFailed(serviceType: String, error: Int) { Log.w(TAG, "Discovery failed: $error") }
        override fun onStopDiscoveryFailed(serviceType: String, error: Int) = Unit
        override fun onServiceFound(info: NsdServiceInfo) {
            if (!isCurrent) return
            synchronized(this@Discovery) {
                visible.add(info.serviceName)
                attempts.remove(info.serviceName)
            }
            enqueue(info)
        }
        override fun onServiceLost(info: NsdServiceInfo) {
            if (!isCurrent) return
            val id = synchronized(this@Discovery) {
                visible.remove(info.serviceName)
                attempts.remove(info.serviceName)
                resolveQueue.removeAll { it.serviceName == info.serviceName }
                idsByServiceName.remove(info.serviceName)
            } ?: return
            Ferry.macs.update { it - id }
        }
    }

    fun start() {
        val info = NsdServiceInfo().apply {
            serviceName = Ferry.deviceName
            serviceType = Wire.SERVICE_TYPE
            port = this@Discovery.port
            setAttribute("id", Store.deviceId)
            setAttribute("name", Ferry.deviceName)
            setAttribute("kind", "android")
        }
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration)
        startBrowsing()
    }

    /**
     * Drops what's known and searches again. A running search can go quiet (the Wi-Fi changed, or the
     * phone froze the app), and it only reports changes, so this is what brings the Mac list back.
     */
    fun refresh() {
        synchronized(this) { reset() }
        Ferry.macs.value = emptyMap()
        startBrowsing()
    }

    fun stop() {
        synchronized(this) { reset() }
        runCatching { nsd.unregisterService(registration) }
        Ferry.macs.value = emptyMap()
    }

    /** Ends the current search and everything queued for it. */
    private fun reset() {
        handler.removeCallbacksAndMessages(null)
        generation++
        resolving = false
        resolveQueue.clear()
        attempts.clear()
        visible.clear()
        idsByServiceName.clear()
        browse?.let { old -> runCatching { nsd.stopServiceDiscovery(old) } }
        browse = null
    }

    private fun startBrowsing() {
        val listener = Browse()
        synchronized(this) { browse = listener }
        nsd.discoverServices(Wire.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    // NsdManager.resolveService handles one request at a time, so resolve found services in turn.
    private fun enqueue(info: NsdServiceInfo) {
        synchronized(this) {
            resolveQueue.addLast(info)
            if (resolving) return
            resolving = true
        }
        resolveNext()
    }

    private fun resolveNext() {
        val (next, current) = synchronized(this) {
            val item = resolveQueue.removeFirstOrNull()
            if (item == null) resolving = false
            item to generation
        }
        if (next == null) return

        // Resolving a Mac that has just left may never call back, which would stall every Mac behind it.
        val finished = AtomicBoolean(false)
        lateinit var timeout: Runnable
        fun finish(retry: Boolean) {
            if (!finished.compareAndSet(false, true)) return
            handler.removeCallbacks(timeout)
            if (synchronized(this) { generation != current }) return
            if (retry) retryLater(next)
            resolveNext()
        }
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, error: Int) = finish(retry = true)
            override fun onServiceResolved(info: NsdServiceInfo) = finish(retry = !onResolved(info))
        }
        timeout = Runnable {
            if (Build.VERSION.SDK_INT >= 34) runCatching { nsd.stopServiceResolution(listener) }
            finish(retry = true)
        }
        handler.postDelayed(timeout, RESOLVE_TIMEOUT_MS)
        @Suppress("DEPRECATION")
        nsd.resolveService(next, listener)
    }

    // A service that just restarted is often announced before its address records; try again shortly.
    private fun retryLater(info: NsdServiceInfo) {
        val attempt = synchronized(this) { (attempts[info.serviceName] ?: 0) + 1 }
        if (attempt > MAX_ATTEMPTS) return
        synchronized(this) { attempts[info.serviceName] = attempt }
        handler.postDelayed({
            if (synchronized(this) { info.serviceName in visible }) enqueue(info)
        }, attempt * 1_500L)
    }

    /** Returns false when the service resolved without enough detail to use, so it's worth retrying. */
    private fun onResolved(info: NsdServiceInfo): Boolean {
        val attributes = info.attributes
        fun attribute(key: String) = attributes[key]?.let { String(it, Charsets.UTF_8) }
        val id = attribute("id") ?: return false
        if (id == Store.deviceId || attribute("kind") != "mac") return true
        @Suppress("DEPRECATION")
        val host = info.host?.hostAddress ?: return false
        synchronized(this) {
            if (info.serviceName !in visible) return true
            idsByServiceName[info.serviceName] = id
            attempts.remove(info.serviceName)
        }
        Ferry.macs.update { it + (id to MacService(id, attribute("name") ?: info.serviceName, host, info.port)) }
        return true
    }

    private companion object {
        const val TAG = "Ferry"
        const val MAX_ATTEMPTS = 4
        const val RESOLVE_TIMEOUT_MS = 8_000L
    }
}
