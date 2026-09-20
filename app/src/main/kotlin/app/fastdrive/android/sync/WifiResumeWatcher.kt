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
 * True when [network] is exactly [alreadyConnectedNetwork] — the network [WifiResumeWatcher.start]
 * captured as already active right before registering its callback. `Network.equals()` compares by
 * the platform's own `netId`, which is assigned fresh on every new connection event, so this is
 * `false` for a genuine reconnection even to the same SSID.
 *
 * A `null` [alreadyConnectedNetwork] means "there is no baseline to ignore" — nothing is
 * pre-existing, so even the callback registration delivers synchronously for the currently-active
 * network counts as a real match. [WifiResumeWatcher.start] passes `null` deliberately when
 * re-arming an already-persisted pause (see its `evaluateExistingConnection` parameter).
 *
 * Pulled out as a pure top-level function (same pattern as [matchesTargetNetwork]) so the fix for
 * Finding #1 (Phase 4 fix round) — never treating registration's own synchronous "already
 * satisfies" callback as a new connection — is directly unit-testable.
 */
fun isPreExistingConnection(network: Network, alreadyConnectedNetwork: Network?): Boolean =
    network == alreadyConnectedNetwork

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

    /**
     * The network [start] decided to ignore callbacks for, or `null` if it is evaluating every
     * network including the already-connected one. Exposed (read-only) purely so a test can assert
     * which of the two [start] modes was taken without reaching into the platform.
     */
    var ignoredBaselineNetwork: Network? = null
        private set

    /**
     * Starts watching for a network satisfying [condition]. A no-op call to [stop] first makes
     * this safe to call repeatedly (e.g. re-arming on every app start) without leaking callbacks.
     *
     * [evaluateExistingConnection] decides what the network the device is ALREADY on at
     * registration time means, which is genuinely different between this function's two call
     * sites (Round 2, Bug 2):
     *
     * - `false` — the user is setting a NEW pause right now from the settings screen
     *   (`SyncSettingsScreen.applyPause`). `registerNetworkCallback()` delivers an immediate
     *   `onCapabilitiesChanged` for any already-matching network, and honouring it would clear the
     *   pause the instant it was set, silently no-op-ing what the user just asked for (Finding #1
     *   of the previous fix round). So the already-active network is captured and callbacks for
     *   that exact [Network] are ignored. A genuine (re)connection, even to the same SSID, gets a
     *   fresh `netId` from the platform and so is never mistaken for it.
     *
     * - `true` — an EXISTING, already-persisted pause is being re-armed on a fresh process start
     *   (`MainActivity.onCreate`). Here the user has been waiting (possibly since yesterday) for
     *   this network and is on it now, so the pause must resolve immediately rather than wait for
     *   a reconnection that may never come. Ignoring the already-active network forever was this
     *   round's Bug 2. No baseline is captured, so registration's own synchronous delivery counts
     *   as a real match and takes the same resume path a later connection would.
     */
    fun start(syncSettings: SyncSettings, condition: PauseCondition, evaluateExistingConnection: Boolean) {
        stop()
        ignoredBaselineNetwork = null
        if (condition !is PauseCondition.AnyWifi && condition !is PauseCondition.SpecificWifi) return
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val baseline = if (evaluateExistingConnection) null else cm.activeNetwork
        ignoredBaselineNetwork = baseline
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        val cb = createResumeCallback(syncSettings, condition, baseline)
        // Recorded BEFORE registering: with evaluateExistingConnection = true the very first
        // delivery can resolve the pause and call stop(), and stop() can only unregister a
        // callback it already knows about. Registration failure below undoes this.
        callback = cb
        // Finding #3 (Phase 4 fix round): registerNetworkCallback (like unregisterNetworkCallback
        // in stop() below) can throw if this app has already hit the platform's per-uid callback
        // registration cap — defensive here for the same reason stop()'s unregister already is.
        val registered = runCatching { cm.registerNetworkCallback(request, cb) }.isSuccess
        if (!registered) callback = null
    }

    private fun createResumeCallback(
        syncSettings: SyncSettings,
        condition: PauseCondition,
        baselineNetwork: Network?,
    ): ConnectivityManager.NetworkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            handleNetworkCandidate(syncSettings, condition, network, baselineNetwork, currentSsid(capabilities))
        }
    }

    /**
     * The whole body of the registered callback, with the SSID already extracted from the
     * capabilities — decide whether [network] resolves [condition], and if it does, stop watching
     * and take the shared resume path ([PauseResumeWorker.clearPauseAndReapply], the same one
     * [PauseResumeWorker.resumeNow] uses). Returns whether the pause was resolved.
     *
     * Public, and taking [baselineNetwork] / [connectedSsid] as plain parameters, so a test can
     * drive the REAL decision-and-resume behavior ("did this pause actually clear?") for either
     * [start] mode, rather than only unit-testing the pure predicates it happens to call — that
     * narrow-test habit is exactly what let both previous rounds' regressions ship.
     */
    fun handleNetworkCandidate(
        syncSettings: SyncSettings,
        condition: PauseCondition,
        network: Network,
        baselineNetwork: Network?,
        connectedSsid: String?,
    ): Boolean {
        if (isPreExistingConnection(network, baselineNetwork)) return false
        if (!matchesTargetNetwork(condition, connectedSsid)) return false
        stop()
        PauseResumeWorker.clearPauseAndReapply(context, syncSettings)
        return true
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
