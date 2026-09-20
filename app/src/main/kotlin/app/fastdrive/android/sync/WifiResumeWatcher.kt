package app.fastdrive.android.sync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

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
 * Process-wide record of when this process FIRST observed each [Network].
 *
 * Every stamp here is an `SystemClock.elapsedRealtime()` value (Round 4 / Fix B): milliseconds
 * since boot, monotonic, and — unlike `System.currentTimeMillis()` — completely unaffected by an
 * NTP correction or a user changing the device clock. Everything this file compares lives in that
 * one domain, so a wall-clock jump can never flip a comparison and clear a pause.
 *
 * Deliberately process-scoped (a top-level `object`), NOT per-[WifiResumeWatcher] and NOT
 * per-Activity: `MainActivity` builds a fresh watcher on every Activity creation, so anything
 * stored on the watcher instance is wiped by a mere screen rotation. That is exactly how Round 2's
 * fix lost the "this network was already connected when the pause was set" fact and started
 * silently clearing freshly-set pauses after a rotation.
 *
 * For the same reason, this registry is NEVER cleared by [WifiResumeWatcher.stop] — `stop()` runs
 * on `onDestroy()`, i.e. immediately before the recreation whose `onCreate()` must still be able to
 * tell "same old network" from "a network that arrived after the pause".
 *
 * Entries are only ever added for Wi-Fi networks seen while a Wi-Fi pause is armed, so the map
 * stays a handful of entries for the lifetime of the process; it is not worth evicting from.
 */
object NetworkFirstSeen {
    /**
     * First-seen stamp for a network that was ALREADY connected the first time this process looked
     * at it. We cannot know when it actually connected — only that it predates our first look — so
     * it is recorded as "older than any pause", which is the fail-closed direction (never
     * auto-resume a pause on a network we didn't watch arrive).
     */
    const val CONNECTED_BEFORE_WE_LOOKED = Long.MIN_VALUE

    private val firstSeen = ConcurrentHashMap<Network, Long>()

    /**
     * Records [stampElapsedRealtime] as [network]'s first-seen stamp if this process has never seen
     * it before, and returns the stamp that is now on record (the pre-existing one if there was
     * one — the earliest record is always the most truthful).
     */
    fun record(network: Network, stampElapsedRealtime: Long): Long =
        firstSeen.putIfAbsent(network, stampElapsedRealtime) ?: stampElapsedRealtime

    /**
     * Records [network] as first seen at [nowElapsedRealtime] if this process has never seen it
     * before, and returns the first-seen stamp that is now on record.
     *
     * A genuine (re)connection — including reconnecting to the same SSID — always gets a fresh
     * `netId` from the platform and therefore a fresh entry here, so it reads as "arrived at
     * [nowElapsedRealtime]" even when the SSID is one we have seen before.
     */
    fun recordSeen(network: Network, nowElapsedRealtime: Long = SystemClock.elapsedRealtime()): Long =
        record(network, nowElapsedRealtime)

    /** `null` when this process has never seen [network]. */
    fun firstSeenElapsedRealtime(network: Network): Long? = firstSeen[network]

    /** Test-only: the registry is process-scoped by design, so tests must be able to isolate. */
    fun clearForTest() {
        firstSeen.clear()
    }
}

/**
 * Process-wide record of which pauses this process has already armed the watcher for, identified by
 * the pause's own set-at stamp ([SyncSettings.getPauseSetAtElapsedRealtime]).
 *
 * This is the ONLY thing that distinguishes Round 4's Situation A from Situation D, and it is
 * deliberately a fact about this process — "have I already taken a census for this exact pause?" —
 * not a boolean a call site passes in. Round 2 failed precisely because the equivalent knowledge
 * was a caller-supplied flag that `MainActivity.onCreate` got wrong after a rotation; no call site
 * can get this wrong, because no call site is consulted.
 *
 * Process-scoped for the same reason as [NetworkFirstSeen]: an Activity recreation must not be able
 * to make the watcher forget that it already armed for this pause.
 */
object ArmedPauses {
    private val armed: MutableSet<Long> = Collections.newSetFromMap(ConcurrentHashMap<Long, Boolean>())

    /**
     * Records that the watcher is arming for the pause stamped [pauseSetAtElapsedRealtime], and
     * returns `true` if this is the FIRST arm for that pause in this process (i.e. the
     * `SyncSettingsScreen.applyPause` call that immediately follows
     * [SyncSettings.setPauseCondition]), `false` for every later re-arm (an Activity recreation, a
     * `MainActivity.onCreate` after the app was backgrounded).
     */
    fun recordArm(pauseSetAtElapsedRealtime: Long): Boolean = armed.add(pauseSetAtElapsedRealtime)

    /** Test-only: process-scoped by design, so tests must be able to isolate. */
    fun clearForTest() {
        armed.clear()
    }
}

/**
 * THE INVARIANT this whole file exists to protect, in BOTH of its halves (Round 4 completes the
 * second half, which Round 3 left out):
 *
 *   **A network that was connected before the pause was set must never auto-resolve that pause —
 *   and a network that genuinely connected after the pause was set must always resolve it, even if
 *   nothing was watching at the moment it happened.**
 *
 * This is decided purely from state that is true about the world (when the pause was set, when
 * this process started, when this process first saw this network, whether this process has already
 * armed for this pause) and never from which function called [WifiResumeWatcher.start]. Two
 * previous fix rounds keyed this decision on the caller — first on "was this the registration-time
 * callback", then on an `evaluateExistingConnection` boolean each call site had to pass correctly —
 * and both silently cleared a pause the user had just set, because a call site got it wrong.
 *
 * Every timestamp below is an `elapsedRealtime` value (milliseconds since boot). See
 * [NetworkFirstSeen] for why, and [platformProcessStartElapsedRealtime] for where the process's own
 * stamp comes from.
 *
 * The three ways a pause may legitimately resolve on the network we are already on:
 *
 * 1. [pauseSetAtElapsedRealtime] > [nowElapsedRealtime] — impossible within one boot, so the device
 *    has rebooted since the pause was set. The pause therefore certainly predates this process, and
 *    behaves exactly like case 2.
 * 2. [pauseSetAtElapsedRealtime] < [processStartElapsedRealtime] — the pause was set before this
 *    process existed (yesterday's "pause until I'm on HomeWifi", app killed since). Everything
 *    visible to us now is news to this process, so an already-connected matching network resolves it
 *    immediately rather than waiting for a reconnection that may never come.
 * 3. [networkFirstSeenElapsedRealtime] > [pauseSetAtElapsedRealtime] — this process's record for
 *    this exact [Network] says it arrived after the pause was set. That record comes either from
 *    watching it connect (the callback path) or from [WifiResumeWatcher.start]'s re-arm census —
 *    see [censusStampForUnseenNetwork], which is what makes Situation D work.
 *
 * Everything else — including "the pause was set 5 seconds ago on the very network we are still
 * sitting on, and the Activity has been recreated three times since" — must NOT resolve.
 */
fun shouldResolvePause(
    pauseSetAtElapsedRealtime: Long,
    processStartElapsedRealtime: Long,
    networkFirstSeenElapsedRealtime: Long,
    nowElapsedRealtime: Long,
): Boolean {
    // A stamp in the future can only mean the device rebooted since the pause was set (elapsedRealtime
    // restarts at zero on boot), which puts the pause definitively before this process.
    if (pauseSetAtElapsedRealtime > nowElapsedRealtime) return true
    return pauseSetAtElapsedRealtime < processStartElapsedRealtime ||
        networkFirstSeenElapsedRealtime > pauseSetAtElapsedRealtime
}

/**
 * How [WifiResumeWatcher.start]'s initial census must record a network it finds already connected
 * and has NO existing [NetworkFirstSeen] record for — Round 4's Fix A (NEW-9).
 *
 * The census cannot look into the past, so it has exactly one usable signal: has this process
 * already armed for this same pause?
 *
 *  - **First arm** ([isFirstArmSincePauseWasSet] `true`) — this is the `start()` that
 *    `SyncSettingsScreen.applyPause` makes microseconds after writing the pause. Anything connected
 *    right now was connected AT the moment the user set the pause, so it is
 *    [NetworkFirstSeen.CONNECTED_BEFORE_WE_LOOKED] and must never resolve it (Situation A, and
 *    Situation C once the record survives into the re-arm).
 *  - **Re-arm** (`false`) — the watcher was stopped (Activity destroyed / app backgrounded) and is
 *    being armed again for a pause this process ALREADY censused. Anything connected now that has no
 *    record therefore was NOT connected at census time, so it connected while we weren't watching,
 *    i.e. after the pause: stamp it [nowElapsedRealtime] so it resolves (Situation D). A network
 *    that WAS connected at census time keeps its original record — [NetworkFirstSeen.record] is
 *    putIfAbsent — so this branch cannot regress Situation A or C.
 *
 * Restricted to [pauseSetAtElapsedRealtime] being inside this process's own lifetime
 * (`processStart <= pauseSetAt <= now`): only then do we have positive, first-hand knowledge of when
 * the pause was set. Outside that window the pause predates this process (or the boot), which
 * [shouldResolvePause]'s clauses 1 and 2 already resolve on their own, so the fail-closed stamp is
 * both correct and irrelevant there.
 */
fun censusStampForUnseenNetwork(
    isFirstArmSincePauseWasSet: Boolean,
    pauseSetAtElapsedRealtime: Long,
    processStartElapsedRealtime: Long,
    nowElapsedRealtime: Long,
): Long {
    val pauseWasSetDuringThisProcess = pauseSetAtElapsedRealtime >= processStartElapsedRealtime &&
        pauseSetAtElapsedRealtime <= nowElapsedRealtime
    return if (!isFirstArmSincePauseWasSet && pauseWasSetDuringThisProcess) {
        nowElapsedRealtime
    } else {
        NetworkFirstSeen.CONNECTED_BEFORE_WE_LOOKED
    }
}

/**
 * Milliseconds since boot at which this process started, straight from the platform
 * (`android.os.Process.getStartElapsedRealtime()`, API 24+, well under this app's `minSdk` of 29).
 *
 * Round 4 / Fix B: this used to be converted into wall-clock terms
 * (`currentTimeMillis() - (elapsedRealtime() - processStartElapsedRealtime)`) and compared against a
 * wall-clock pause stamp. That mixed two time domains: an NTP correction between "user set the
 * pause" and "Activity created" shifted the derived process start relative to the stored pause
 * stamp, and a large enough forward jump made a brand-new pause look older than this process —
 * clearing it through a timing bug rather than a real reconnection. It is now used, stored and
 * compared purely in the `elapsedRealtime` domain, which no clock change can touch.
 *
 * Read from the platform rather than captured in an initializer on purpose: an initializer records
 * when this class happened to be first loaded, which in the "user sets a pause" path happens
 * *after* the pause was written — making a brand-new pause look older than the process and
 * resolving it instantly.
 *
 * If the platform call is unavailable (a unit-test environment without this shadow), it falls back
 * to [Long.MIN_VALUE] — "this process is older than any pause" — which disables clause 2 of
 * [shouldResolvePause] entirely. That is the fail-closed direction: a pause is never silently
 * cleared, at worst it waits for a real connection event.
 */
fun platformProcessStartElapsedRealtime(): Long = runCatching {
    android.os.Process.getStartElapsedRealtime()
}.getOrDefault(Long.MIN_VALUE)

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
 *
 * [start] takes no "should an already-connected network count?" flag: see [shouldResolvePause].
 *
 * The three constructor parameters after [context] are environment probes (when did this process
 * start, what time is it, what is connected) with real platform defaults, injectable so tests can
 * place events in an exact order. None of them describes a CALLER's intent — that is the shape that
 * failed in Round 2 — and production never passes any of them.
 */
class WifiResumeWatcher(
    private val context: Context,
    private val processStartElapsedRealtime: Long = platformProcessStartElapsedRealtime(),
    private val nowElapsedRealtime: () -> Long = { SystemClock.elapsedRealtime() },
    private val connectedNetworks: (ConnectivityManager) -> List<Network> = ::platformConnectedNetworks,
) {
    /**
     * Written on the main thread by [start], but read AND written by [stop], which
     * [handleNetworkCandidate] calls from `ConnectivityManager`'s own callback thread —
     * `@Volatile` so that cross-thread write is visible (matching this codebase's existing
     * convention for a mutable field touched from more than one thread, `AppDatabase.instance`).
     */
    @Volatile
    private var callback: ConnectivityManager.NetworkCallback? = null

    /**
     * Starts watching for a network satisfying [condition]. A no-op call to [stop] first makes
     * this safe to call repeatedly (re-arming on every app start, on every Activity recreation,
     * and whenever the user sets a new pause) without leaking callbacks.
     *
     * Every call site calls this the same way. Whether a network the device is already on may
     * resolve the pause is decided from real state in [handleNetworkCandidate] — see
     * [shouldResolvePause] for the invariant and why no caller gets a say in it.
     */
    fun start(syncSettings: SyncSettings, condition: PauseCondition) {
        stop()
        if (condition !is PauseCondition.AnyWifi && condition !is PauseCondition.SpecificWifi) return
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        // Record everything already connected BEFORE registering, so registration's own synchronous
        // "this already matches" delivery cannot be mistaken for a network we watched arrive. How an
        // UNRECORDED network is stamped depends on whether this process has already censused this
        // same pause — see censusStampForUnseenNetwork. A network already on record (e.g. from the
        // arming that happened when the user set the pause, before a rotation destroyed the previous
        // watcher instance) keeps its original, truthful stamp either way.
        val pauseSetAt = syncSettings.getPauseSetAtElapsedRealtime()
        val now = nowElapsedRealtime()
        val stamp = censusStampForUnseenNetwork(
            isFirstArmSincePauseWasSet = ArmedPauses.recordArm(pauseSetAt),
            pauseSetAtElapsedRealtime = pauseSetAt,
            processStartElapsedRealtime = processStartElapsedRealtime,
            nowElapsedRealtime = now,
        )
        connectedNetworks(cm).forEach { NetworkFirstSeen.record(it, stamp) }
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        val cb = createResumeCallback(syncSettings, condition)
        // Recorded BEFORE registering: the very first delivery can resolve the pause and call
        // stop(), and stop() can only unregister a callback it already knows about. Registration
        // failure below undoes this.
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
    ): ConnectivityManager.NetworkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            handleNetworkCandidate(syncSettings, condition, network, currentSsid(capabilities))
        }
    }

    /**
     * The whole body of the registered callback, with the SSID already extracted from the
     * capabilities — decide whether [network] resolves [condition], and if it does, stop watching
     * and take the shared resume path ([PauseResumeWorker.clearPauseAndReapply], the same one
     * [PauseResumeWorker.resumeNow] uses). Returns whether the pause was resolved.
     *
     * Public, and taking [connectedSsid] as a plain parameter, so a test can drive the REAL
     * decision-and-resume behavior ("did this pause actually clear?") rather than only unit-testing
     * the pure predicates it happens to call — that narrow-test habit is what let both previous
     * rounds' regressions ship. Note there is no parameter describing the CALLER's intent: the
     * decision below reads only stored state.
     */
    fun handleNetworkCandidate(
        syncSettings: SyncSettings,
        condition: PauseCondition,
        network: Network,
        connectedSsid: String?,
    ): Boolean {
        if (!matchesTargetNetwork(condition, connectedSsid)) return false
        // Nothing is paused any more — this pause was already resolved (by us, by the Resume now
        // button, or by the timer worker) and a still-registered callback must never "resolve" it a
        // second time. Without this, a cleared pause reads back as PAUSE_SET_AT_UNKNOWN, which is
        // "older than anything" and would re-fire the resume path on every capabilities change.
        if (syncSettings.getStoredPauseCondition() == null) {
            stop()
            return false
        }
        // INVARIANT (enforced here, for every caller, present and future): a network connected
        // before this pause was set must never auto-resolve it, and one that genuinely connected
        // after it must always resolve it. The four inputs below are facts about the world — when
        // the user set the pause, when this process began, when this process first laid eyes on this
        // exact Network, and what time it is — never a flag the caller chose. See shouldResolvePause.
        val now = nowElapsedRealtime()
        val firstSeen = NetworkFirstSeen.recordSeen(network, now)
        val resolve = shouldResolvePause(
            pauseSetAtElapsedRealtime = syncSettings.getPauseSetAtElapsedRealtime(),
            processStartElapsedRealtime = processStartElapsedRealtime,
            networkFirstSeenElapsedRealtime = firstSeen,
            nowElapsedRealtime = now,
        )
        if (!resolve) return false
        stop()
        PauseResumeWorker.clearPauseAndReapply(context, syncSettings)
        return true
    }

    /**
     * Unregisters the callback if one is active. Safe to call when already stopped.
     *
     * Deliberately does NOT touch [NetworkFirstSeen] or [ArmedPauses]: this runs on
     * `MainActivity.onDestroy()`, and the very next thing that happens after a rotation is an
     * `onCreate()` that needs those records to know the network it is looking at is the same one the
     * user set the pause on — and to know that it is re-arming rather than arming for the first time.
     */
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

/** Every network connected right now, as best the platform will tell us. `getAllNetworks()` is
 *  deprecated at API 31 but is the only enumeration available at this app's `minSdk` of 29;
 *  `activeNetwork` is folded in as a fallback for anything it doesn't report. */
fun platformConnectedNetworks(cm: ConnectivityManager): List<Network> {
    @Suppress("DEPRECATION")
    val all = runCatching { cm.allNetworks.toList() }.getOrDefault(emptyList())
    val active = runCatching { cm.activeNetwork }.getOrNull()
    return (all + listOfNotNull(active)).distinct()
}
