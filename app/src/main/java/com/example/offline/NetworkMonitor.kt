package com.example.offline

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether Android has a working internet connection: the default network has internet and was
 * validated (Android reached the internet through it). A network that is connected but carries
 * nothing (one bar) is not seen here; the switch to downloaded songs times out on that instead.
 */
class NetworkMonitor(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val _online = MutableStateFlow(currentlyOnline())
    val online: StateFlow<Boolean> = _online.asStateFlow()

    /** When [online] last changed (elapsed realtime). */
    @Volatile
    var changedAt: Long = SystemClock.elapsedRealtime()
        private set

    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = set(usable(caps))
        override fun onLost(network: Network) = set(false)
        override fun onUnavailable() = set(false)
    }

    fun start() {
        if (registered || cm == null) return
        try {
            cm.registerDefaultNetworkCallback(callback)
            registered = true
        } catch (_: Exception) {
            // Too many callbacks registered, or no permission: assume online, timers still work.
        }
    }

    fun stop() {
        if (!registered) return
        try {
            cm?.unregisterNetworkCallback(callback)
        } catch (_: Exception) {
        }
        registered = false
    }

    /** Online without a break for at least [ms]. */
    fun onlineFor(ms: Long): Boolean = _online.value && SystemClock.elapsedRealtime() - changedAt >= ms

    private fun set(value: Boolean) {
        if (_online.value == value) return
        changedAt = SystemClock.elapsedRealtime()
        _online.value = value
    }

    private fun currentlyOnline(): Boolean = try {
        cm?.getNetworkCapabilities(cm.activeNetwork)?.let(::usable) ?: false
    } catch (_: Exception) {
        true
    }

    private fun usable(caps: NetworkCapabilities): Boolean =
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
