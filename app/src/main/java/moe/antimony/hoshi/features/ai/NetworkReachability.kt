package moe.antimony.hoshi.features.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.IOException

/**
 * A single "is there a usable network right now" answer, used to decide whether a manga bubble tap
 * should be served from the offline pre-translation cache instead of a cloud request.
 *
 * Deliberately advisory, not authoritative: a validated network can still fail (captive portal,
 * dead uplink), so the reader also falls back to the cache when a live request fails with a network
 * error. This just avoids a pointless timeout in the common airplane-mode case.
 *
 * Mirrors the iOS `NetworkReachability`.
 */
object NetworkReachability {

    /** False when the device has no usable route (airplane mode, no Wi-Fi/cellular). */
    fun isOnline(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * True when [error] looks like "the network was not reachable", as opposed to a server-side
     * rejection such as a bad API key — only the former should fall back to the cache.
     */
    fun isNetworkFailure(error: Throwable): Boolean {
        // The cloud clients wrap everything in their own exception type, so a bare `is IOException`
        // check here would never match and the whole offline fallback would be dead code.
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 8) {
            if (current is IOException) return true
            current = current.cause
            depth++
        }
        return false
    }
}
