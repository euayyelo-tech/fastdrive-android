package app.fastdrive.android.sync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import androidx.core.content.ContextCompat

/** What `WifiInfo.getSSID()` returns when it can't/won't report a real SSID (no permission, no
 *  active Wi-Fi connection, etc.) — never treated as a real network name. */
private const val UNKNOWN_SSID = "<unknown ssid>"

/**
 * Pure matching logic for Phase 4 Task 4's Wi-Fi-based auto-resume: does the currently-connected
 * network (already confirmed to be Wi-Fi by the caller's [NetworkRequest]) satisfy [condition]?
 *
 * [connectedSsid] is `null` whenever the real SSID isn't available — permission not granted, no
 * `WifiInfo` on the capabilities, or the platform itself returned [UNKNOWN_SSID] — and a `null`
 * SSID never matches [PauseCondition.SpecificWifi], it only fails closed (never auto-resumes
 * rather than resuming on the wrong network).
 *
 * `WifiInfo.getSSID()` wraps a normal UTF-8 SSID in double quotes (e.g. `"MyNetwork"`); a hex
 * SSID (non-UTF-8 bytes) comes back unquoted. [SyncSettings] stores whatever the user typed for
 * [PauseCondition.SpecificWifi.ssid] with no quotes, so the quoted form is stripped before
 * comparing — `trim('"')` is a deliberately narrow fix for exactly that one quirk (a real SSID
 * that itself starts and ends with `"` would also get stripped, but that's a vanishingly rare
 * edge case not worth extra complexity here).
 */
fun matchesTargetNetwork(condition: PauseCondition, connectedSsid: String?): Boolean = when (condition) {
    is PauseCondition.AnyWifi -> true // caller only invokes this once TRANSPORT_WIFI is confirmed
    is PauseCondition.SpecificWifi -> connectedSsid != null && connectedSsid.trim('"') == condition.ssid
    else -> false
}

/**
 * Watches for a Wi-Fi connection that satisfies a [PauseCondition.AnyWifi] or
 * [PauseCondition.SpecificWifi] pause, and clears the pause (resuming whichever [SyncSettings]
 * trigger mechanism is selected) the moment it does.
 *
 * SSID reading: [android.net.wifi.WifiManager.getConnectionInfo] is deprecated since API 31.
 * Since this app's `minSdk` is 29, the current non-deprecated replacement is used instead —
 * [NetworkCapabilities.getTransportInfo] on the capabilities the [ConnectivityManager.NetworkCallback]
 * itself already hands over in [ConnectivityManager.NetworkCallback.onCapabilitiesChanged], cast to
 * [WifiInfo] (added in API 29, matching this app's `minSdk` exactly). That avoids a second,
 * separate `WifiManager` lookup entirely.
 *
 * Either way — `WifiManager.getConnectionInfo()` or `NetworkCapabilities.getTransportInfo()` — the
 * platform still gates a *real* SSID behind `ACCESS_FINE_LOCATION`: without it, `WifiInfo.getSSID()`
 * returns [UNKNOWN_SSID] regardless of which API produced the `WifiInfo`. [currentSsid] checks the
 * permission itself first (rather than relying on the sentinel string) so a denied/revoked
 * permission degrades to "no SSID available" — [PauseCondition.SpecificWifi] simply never matches,
 * it never crashes and never silently resumes on the wrong network.
 */
class WifiResumeWatcher(private val context: Context) {
    private var callback: ConnectivityManager.NetworkCallback? = null

    /** Starts watching for a network satisfying [condition]. A no-op call to [stop] first makes
     *  this safe to call repeatedly (e.g. re-arming on every app start) without leaking callbacks. */
    fun start(syncSettings: SyncSettings, condition: PauseCondition) {
        stop()
        if (condition !is PauseCondition.AnyWifi && condition !is PauseCondition.SpecificWifi) return
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (matchesTargetNetwork(condition, currentSsid(capabilities))) {
                    stop()
                    syncSettings.setPauseCondition(null)
                    PeriodicSyncWorker.applySettings(context, syncSettings)
                    InstantSyncService.applySettings(context, syncSettings)
                }
            }
        }
        cm.registerNetworkCallback(request, cb)
        callback = cb
    }

    /** Unregisters the callback if one is active. Safe to call when already stopped. */
    fun stop() {
        val cb = callback ?: return
        val cm = context.getSystemService(ConnectivityManager::class.java)
        // registerNetworkCallback/unregisterNetworkCallback can throw IllegalArgumentException if
        // the callback was already unregistered by the system (e.g. app backgrounded long enough
        // to hit the platform's callback-count cleanup) — defensive, matches this codebase's other
        // "never crash on an OS-driven edge case" spots (e.g. InstantSyncService's own catches).
        runCatching { cm?.unregisterNetworkCallback(cb) }
        callback = null
    }

    /** `null` whenever a real SSID isn't available: permission not granted, no [WifiInfo] on the
     *  capabilities, or the platform's own "no SSID" sentinel. */
    private fun currentSsid(capabilities: NetworkCapabilities): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        val wifiInfo = capabilities.transportInfo as? WifiInfo ?: return null
        val ssid = wifiInfo.ssid
        return if (ssid.isNullOrEmpty() || ssid == UNKNOWN_SSID) null else ssid
    }
}
