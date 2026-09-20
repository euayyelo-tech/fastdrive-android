package app.fastdrive.android.sync

import android.app.Application
import android.net.Network
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowNetwork

/**
 * Phase 4 Task 4's Wi-Fi resume watcher.
 *
 * The heart of this file is the matched invariant PAIR further down: two tests with an identical
 * pause condition, SSID, network and arming sequence, differing only in whether the network was
 * already connected when the pause was set. One must never resolve the pause; the other must always
 * resolve it. Round 3 only encoded the first half, which is why a network that connected while the
 * app was backgrounded never resumed sync (Round 4, NEW-9 / Situation D).
 *
 * Three earlier fix rounds shipped a regression because the decision was keyed on which function
 * called [WifiResumeWatcher.start]; these tests are written so that a caller cannot influence them
 * at all — the only things injected are environment probes (process start, the clock, what is
 * connected), never an intent.
 *
 * Every timestamp here is an `elapsedRealtime` value (milliseconds since boot), matching what the
 * production code compares. Clocks are injected rather than shadowed, following this codebase's
 * existing convention (`SyncSettings.setPauseCondition(nowMillis = …)`,
 * `WifiResumeWatcher(processStartElapsedRealtime = …)`).
 *
 * [ShadowNetwork.newInstance] needs Robolectric's environment to construct a real
 * `android.net.Network`, hence [RobolectricTestRunner] here.
 */
@RunWith(RobolectricTestRunner::class)
class WifiResumeWatcherTest {

    /** [NetworkFirstSeen] and [ArmedPauses] are process-scoped on purpose (they must survive
     *  Activity recreation), so tests have to isolate themselves from each other explicitly. */
    @Before
    fun clearProcessScopedState() {
        NetworkFirstSeen.clearForTest()
        ArmedPauses.clearForTest()
    }

    @After
    fun clearProcessScopedStateAfter() {
        NetworkFirstSeen.clearForTest()
        ArmedPauses.clearForTest()
    }

    // ---------------------------------------------------------------------------------------
    // matchesTargetNetwork: which SSIDs satisfy which condition. Kept away from the timing tests
    // below so the two concerns can never be conflated again.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `AnyWifi always matches once invoked, regardless of SSID`() {
        assertTrue(matchesTargetNetwork(PauseCondition.AnyWifi, "SomeNetwork"))
        assertTrue(matchesTargetNetwork(PauseCondition.AnyWifi, null))
    }

    @Test
    fun `SpecificWifi matches the exact SSID`() {
        val condition = PauseCondition.SpecificWifi("HomeNetwork")
        assertTrue(matchesTargetNetwork(condition, "HomeNetwork"))
    }

    @Test
    fun `SpecificWifi matches a quoted SSID the same as the unquoted form WifiInfo returns`() {
        val condition = PauseCondition.SpecificWifi("HomeNetwork")
        assertTrue(matchesTargetNetwork(condition, "\"HomeNetwork\""))
    }

    @Test
    fun `SpecificWifi does not match a different SSID`() {
        val condition = PauseCondition.SpecificWifi("HomeNetwork")
        assertFalse(matchesTargetNetwork(condition, "CoffeeShopWifi"))
        assertFalse(matchesTargetNetwork(condition, "\"CoffeeShopWifi\""))
    }

    @Test
    fun `SpecificWifi never matches a null SSID (permission not granted or no real SSID available)`() {
        val condition = PauseCondition.SpecificWifi("HomeNetwork")
        assertFalse(matchesTargetNetwork(condition, null))
    }

    @Test
    fun `Timer and Manual conditions never match (this watcher isn't invoked for them, but the fallback is safe)`() {
        assertFalse(matchesTargetNetwork(PauseCondition.Timer(0L), "HomeNetwork"))
        assertFalse(matchesTargetNetwork(PauseCondition.Manual, "HomeNetwork"))
    }

    // ---------------------------------------------------------------------------------------
    // shouldResolvePause: the pure form of the invariant, with no watcher, no caller, no network.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `shouldResolvePause - a pre-pause network does not resolve`() {
        assertFalse(
            shouldResolvePause(
                pauseSetAtElapsedRealtime = PAUSE_SET_AT,
                processStartElapsedRealtime = PROCESS_START,
                networkFirstSeenElapsedRealtime = NetworkFirstSeen.CONNECTED_BEFORE_WE_LOOKED,
                nowElapsedRealtime = PAUSE_SET_AT + 1_000L,
            ),
        )
        assertFalse(
            shouldResolvePause(
                pauseSetAtElapsedRealtime = PAUSE_SET_AT,
                processStartElapsedRealtime = PROCESS_START,
                networkFirstSeenElapsedRealtime = PAUSE_SET_AT - 1L,
                nowElapsedRealtime = PAUSE_SET_AT + 1_000L,
            ),
        )
    }

    @Test
    fun `shouldResolvePause - a network first seen after the pause resolves`() {
        assertTrue(
            shouldResolvePause(
                pauseSetAtElapsedRealtime = PAUSE_SET_AT,
                processStartElapsedRealtime = PROCESS_START,
                networkFirstSeenElapsedRealtime = PAUSE_SET_AT + 1L,
                nowElapsedRealtime = PAUSE_SET_AT + 1_000L,
            ),
        )
    }

    @Test
    fun `shouldResolvePause - a pause older than this process resolves on whatever we are already connected to`() {
        assertTrue(
            shouldResolvePause(
                pauseSetAtElapsedRealtime = PAUSE_SET_AT,
                processStartElapsedRealtime = PAUSE_SET_AT + 60_000L,
                networkFirstSeenElapsedRealtime = NetworkFirstSeen.CONNECTED_BEFORE_WE_LOOKED,
                nowElapsedRealtime = PAUSE_SET_AT + 120_000L,
            ),
        )
    }

    @Test
    fun `shouldResolvePause - a pause with no stored timestamp (older install) behaves like one older than this process`() {
        assertTrue(
            shouldResolvePause(
                pauseSetAtElapsedRealtime = Long.MIN_VALUE,
                processStartElapsedRealtime = PROCESS_START,
                networkFirstSeenElapsedRealtime = NetworkFirstSeen.CONNECTED_BEFORE_WE_LOOKED,
                nowElapsedRealtime = PAUSE_SET_AT,
            ),
        )
    }

    @Test
    fun `shouldResolvePause - a stamp from before a reboot reads as older than this process`() {
        // elapsedRealtime restarts at zero on boot, so a stored stamp larger than "now" can only
        // have been written during a previous boot — the pause certainly predates this process.
        assertTrue(
            shouldResolvePause(
                pauseSetAtElapsedRealtime = 9_000_000L,
                processStartElapsedRealtime = 1_000L,
                networkFirstSeenElapsedRealtime = NetworkFirstSeen.CONNECTED_BEFORE_WE_LOOKED,
                nowElapsedRealtime = 30_000L,
            ),
        )
    }

    // ---------------------------------------------------------------------------------------
    // censusStampForUnseenNetwork: Round 4's Fix A in its pure form — how start()'s census records
    // a connected network it has never seen, which is the ONLY thing separating Situation A from
    // Situation D.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `census - the first arm after a pause is set treats everything connected as pre-existing`() {
        assertEquals(
            NetworkFirstSeen.CONNECTED_BEFORE_WE_LOOKED,
            censusStampForUnseenNetwork(
                isFirstArmSincePauseWasSet = true,
                pauseSetAtElapsedRealtime = PAUSE_SET_AT,
                processStartElapsedRealtime = PROCESS_START,
                nowElapsedRealtime = PAUSE_SET_AT,
            ),
        )
    }

    @Test
    fun `census - a re-arm treats an unseen network as having arrived while we weren't watching`() {
        val now = PAUSE_SET_AT + 30_000L
        assertEquals(
            now,
            censusStampForUnseenNetwork(
                isFirstArmSincePauseWasSet = false,
                pauseSetAtElapsedRealtime = PAUSE_SET_AT,
                processStartElapsedRealtime = PROCESS_START,
                nowElapsedRealtime = now,
            ),
        )
    }

    @Test
    fun `census - a re-arm for a pause set before this process falls back to pre-existing`() {
        // No first-hand knowledge of when that pause was set, so the fail-closed stamp — and
        // shouldResolvePause's "pause predates this process" clause resolves it anyway.
        assertEquals(
            NetworkFirstSeen.CONNECTED_BEFORE_WE_LOOKED,
            censusStampForUnseenNetwork(
                isFirstArmSincePauseWasSet = false,
                pauseSetAtElapsedRealtime = PROCESS_START - 10_000L,
                processStartElapsedRealtime = PROCESS_START,
                nowElapsedRealtime = PAUSE_SET_AT,
            ),
        )
    }

    // ---------------------------------------------------------------------------------------
    // THE MATCHED PAIR. Both halves share the same PauseCondition, the same SSID, the same Network
    // object, the same process start and the same arm / stop / re-arm sequence. The ONLY difference
    // is whether the network was among the connected ones at the moment the pause was set.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a network connected before the pause was set never resolves it`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val syncSettings = pausedSettings(context)
        val homeWifi = ShadowNetwork.newInstance(HOME_WIFI_NET_ID)

        // Arm #1 — SyncSettingsScreen.applyPause, microseconds after the pause was written. The
        // device is ALREADY on HomeWifi: this is the one difference from the test below.
        val atPauseTime = watcher(context, now = PAUSE_SET_AT, connected = listOf(homeWifi))
        atPauseTime.start(syncSettings, CONDITION)
        atPauseTime.stop()

        // Arm #2 — MainActivity.onCreate, half a minute later (a rotation, or reopening the app).
        // Nothing about the world changed: same process, same network, same pause.
        val afterReopen = watcher(context, now = REOPENED_AT, connected = listOf(homeWifi))
        afterReopen.start(syncSettings, CONDITION)
        val resolved = afterReopen.handleNetworkCandidate(syncSettings, CONDITION, homeWifi, REPORTED_SSID)

        assertFalse("the network the user set the pause ON is not the user connecting to it", resolved)
        assertEquals(CONDITION, syncSettings.getPauseCondition())
        assertFalse("the pause the user set must still be in force", isPeriodicWorkActive(context))
        assertEquals(
            NetworkFirstSeen.CONNECTED_BEFORE_WE_LOOKED,
            NetworkFirstSeen.firstSeenElapsedRealtime(homeWifi),
        )
    }

    @Test
    fun `a network connection genuinely occurring after the pause was set always resolves it, even if this process wasn't watching when it happened`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val syncSettings = pausedSettings(context)
        val homeWifi = ShadowNetwork.newInstance(HOME_WIFI_NET_ID)

        // Arm #1 — identical to the test above except for this: HomeWifi is NOT connected yet.
        val atPauseTime = watcher(context, now = PAUSE_SET_AT, connected = emptyList())
        atPauseTime.start(syncSettings, CONDITION)
        // The app is backgrounded and the Activity destroyed; the watcher stops. The user then walks
        // in the door and the device joins HomeWifi with nobody watching.
        atPauseTime.stop()

        // Arm #2 — the user reopens the app. Same process, so nothing was re-read from disk and no
        // callback ever fired for that connection: this census is the only chance to notice it.
        val afterReopen = watcher(context, now = REOPENED_AT, connected = listOf(homeWifi))
        afterReopen.start(syncSettings, CONDITION)
        val resolved = afterReopen.handleNetworkCandidate(syncSettings, CONDITION, homeWifi, REPORTED_SSID)

        assertTrue("connecting while the app was backgrounded must still resume sync", resolved)
        assertNull(syncSettings.getPauseCondition())
        assertTrue(isPeriodicWorkActive(context))
        assertEquals(REOPENED_AT, NetworkFirstSeen.firstSeenElapsedRealtime(homeWifi))
    }

    // ---------------------------------------------------------------------------------------
    // Situations A, B, C, E: the rest of the controlled set.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `Situation A - a pause set while already on the target network is NOT instantly cleared`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val syncSettings = pausedSettings(context)
        assertFalse(isPeriodicWorkActive(context))
        val homeWifi = ShadowNetwork.newInstance(HOME_WIFI_NET_ID)
        val watcher = watcher(context, now = PAUSE_SET_AT, connected = listOf(homeWifi))

        // The very arming applyPause does, then registration's own synchronous delivery.
        watcher.start(syncSettings, CONDITION)
        val resolved = watcher.handleNetworkCandidate(syncSettings, CONDITION, homeWifi, REPORTED_SSID)

        assertFalse(resolved)
        assertEquals(CONDITION, syncSettings.getPauseCondition())
        assertFalse("the pause the user just set must still be in force", isPeriodicWorkActive(context))
    }

    @Test
    fun `Situation B - a pause that predates this process resolves on the already-connected network`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val syncSettings = pausedSettings(context)
        assertFalse(isPeriodicWorkActive(context))
        val homeWifi = ShadowNetwork.newInstance(HOME_WIFI_NET_ID)
        // Identical to Situation A except this: the app was killed and restarted after the pause
        // was set, so everything we can see now is news to this process.
        val watcher = watcher(
            context,
            processStart = PAUSE_SET_AT + 60_000L,
            now = PAUSE_SET_AT + 120_000L,
            connected = listOf(homeWifi),
        )

        watcher.start(syncSettings, CONDITION)
        val resolved = watcher.handleNetworkCandidate(syncSettings, CONDITION, homeWifi, REPORTED_SSID)

        assertTrue(resolved)
        assertNull(syncSettings.getPauseCondition())
        assertTrue(
            "yesterday's Wi-Fi pause must actually restart sync, not wait for a reconnection",
            isPeriodicWorkActive(context),
        )
    }

    @Test
    fun `Situation C - an Activity recreation does not clear a pause set moments ago on this network`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val syncSettings = pausedSettings(context)
        assertFalse(isPeriodicWorkActive(context))
        val homeWifi = ShadowNetwork.newInstance(HOME_WIFI_NET_ID)

        // The watcher armed when the user set the pause (SyncSettingsScreen), then the Activity is
        // destroyed and recreated by a rotation a second later: stop(), a brand-new watcher
        // instance, start(). Nothing about the world changed.
        val beforeRotation = watcher(context, now = PAUSE_SET_AT, connected = listOf(homeWifi))
        beforeRotation.start(syncSettings, CONDITION)
        beforeRotation.stop()

        val afterRotation = watcher(context, now = PAUSE_SET_AT + 1_000L, connected = listOf(homeWifi))
        afterRotation.start(syncSettings, CONDITION)
        val resolved = afterRotation.handleNetworkCandidate(syncSettings, CONDITION, homeWifi, REPORTED_SSID)

        assertFalse("a rotation is not the user reconnecting to the network", resolved)
        assertEquals(CONDITION, syncSettings.getPauseCondition())
        assertFalse(isPeriodicWorkActive(context))
    }

    @Test
    fun `Situation E - a real connection after reopening still resolves the pause, exactly once`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val syncSettings = pausedSettings(context)

        // Pause set while NOT on the target network, app backgrounded without ever connecting...
        val atPauseTime = watcher(context, now = PAUSE_SET_AT, connected = emptyList())
        atPauseTime.start(syncSettings, CONDITION)
        atPauseTime.stop()
        // ...reopened, still not connected: the re-arm census has nothing to record.
        val afterReopen = watcher(context, now = REOPENED_AT, connected = emptyList())
        afterReopen.start(syncSettings, CONDITION)
        assertEquals(CONDITION, syncSettings.getPauseCondition())

        // Now the device genuinely joins HomeWifi and the registered callback fires for real.
        val homeWifi = ShadowNetwork.newInstance(HOME_WIFI_NET_ID)
        val resolved = afterReopen.handleNetworkCandidate(syncSettings, CONDITION, homeWifi, REPORTED_SSID)

        assertTrue("re-arming must not suppress a genuine later connection", resolved)
        assertNull(syncSettings.getPauseCondition())
        assertTrue(isPeriodicWorkActive(context))

        // A second delivery for the same network (capabilities change again before the platform has
        // torn the callback down) must not fire the resume path a second time.
        val resolvedAgain = afterReopen.handleNetworkCandidate(syncSettings, CONDITION, homeWifi, REPORTED_SSID)
        assertFalse("a resolved pause must never resolve twice", resolvedAgain)
    }

    // ---------------------------------------------------------------------------------------
    // Fix B: the comparison lives entirely in the elapsedRealtime domain.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a forward wall-clock jump between pause-set and watcher-start cannot clear the pause`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val syncSettings = pausedSettings(context)
        val homeWifi = ShadowNetwork.newInstance(HOME_WIFI_NET_ID)

        // The pause was armed normally, on the network the user was already sitting on.
        val atPauseTime = watcher(context, now = PAUSE_SET_AT, connected = listOf(homeWifi))
        atPauseTime.start(syncSettings, CONDITION)
        atPauseTime.stop()

        // An NTP correction now jumps the wall clock forward an hour, while only a second of real
        // time passes. Before Round 4 the watcher derived its process-start stamp in wall-clock
        // terms (currentTimeMillis() - time-since-process-start), so after the jump that derived
        // stamp landed AFTER the pause's own wall-clock stamp — "this pause predates this process"
        // — and the pause was cleared by arithmetic rather than by a reconnection:
        val jumpedWallClockNow = PAUSE_SET_AT_WALL + 3_600_000L
        val oldStyleProcessStartWallClock = jumpedWallClockNow - (PAUSE_SET_AT + 1_000L - PROCESS_START)
        assertTrue(
            "this is the arithmetic that used to clear the pause",
            syncSettings.getPauseSetAtMillis() < oldStyleProcessStartWallClock,
        )

        // Nothing the watcher compares is in that domain any more: elapsedRealtime advanced by the
        // one second that actually passed, and the jump is invisible to it.
        val afterJump = watcher(context, now = PAUSE_SET_AT + 1_000L, connected = listOf(homeWifi))
        afterJump.start(syncSettings, CONDITION)
        val resolved = afterJump.handleNetworkCandidate(syncSettings, CONDITION, homeWifi, REPORTED_SSID)

        assertFalse("a clock correction is not a reconnection", resolved)
        assertEquals(CONDITION, syncSettings.getPauseCondition())
        assertFalse(isPeriodicWorkActive(context))
    }

    // ---------------------------------------------------------------------------------------

    /**
     * The reviewer's explicit ask from Round 3: the invariant must hold for call sites that do not
     * exist yet. This test invents one — an anonymous lambda that is neither
     * `SyncSettingsScreen.applyPause` nor `MainActivity.onCreate` — arms the watcher through it
     * repeatedly with a network that was connected before the pause was set, and asserts nothing
     * resolves.
     *
     * If anyone ever reintroduces a caller-supplied "treat the existing connection as a match"
     * parameter, this test is the one that has no plausible value to pass for it.
     */
    @Test
    fun `no call site, named or hypothetical, can make a pre-pause network resolve the pause`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val syncSettings = pausedSettings(context)
        val homeWifi = ShadowNetwork.newInstance(HOME_WIFI_NET_ID)

        val armFromAnUnknownFutureCallSite: (WifiResumeWatcher) -> Unit = { watcher ->
            watcher.start(syncSettings, CONDITION)
        }

        repeat(3) { arming ->
            val watcher = watcher(
                context,
                now = PAUSE_SET_AT + arming * 1_000L,
                connected = listOf(homeWifi),
            )
            armFromAnUnknownFutureCallSite(watcher)
            val resolved = watcher.handleNetworkCandidate(syncSettings, CONDITION, homeWifi, REPORTED_SSID)
            assertFalse("arming #$arming must not resolve a pause on a pre-pause network", resolved)
            watcher.stop()
        }

        assertEquals(CONDITION, syncSettings.getPauseCondition())
        assertFalse(isPeriodicWorkActive(context))
    }

    @Test
    fun `a non-matching network never resolves the pause, whatever the timing`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val syncSettings = pausedSettings(context)
        // Situation B's timing (the one that WOULD resolve on a match) plus a brand-new network,
        // so only the SSID mismatch can be what stops it.
        val watcher = watcher(context, processStart = PAUSE_SET_AT + 60_000L, now = PAUSE_SET_AT + 120_000L)

        val resolved = watcher.handleNetworkCandidate(
            syncSettings, CONDITION, ShadowNetwork.newInstance(2), "CoffeeShopWifi",
        )

        assertFalse(resolved)
        assertEquals(CONDITION, syncSettings.getPauseCondition())
    }

    @Test
    fun `start records the networks already connected, so registration's own delivery cannot resolve`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val syncSettings = pausedSettings(context)
        val homeWifi = ShadowNetwork.newInstance(HOME_WIFI_NET_ID)
        val watcher = watcher(context, now = PAUSE_SET_AT, connected = listOf(homeWifi))

        watcher.start(syncSettings, CONDITION)

        assertEquals(
            NetworkFirstSeen.CONNECTED_BEFORE_WE_LOOKED,
            NetworkFirstSeen.firstSeenElapsedRealtime(homeWifi),
        )
        watcher.stop()
        // stop() must NOT forget it — the next onCreate after a rotation depends on this record.
        assertEquals(
            NetworkFirstSeen.CONNECTED_BEFORE_WE_LOOKED,
            NetworkFirstSeen.firstSeenElapsedRealtime(homeWifi),
        )
    }

    @Test
    fun `start ignores a condition that is not Wi-Fi based`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val syncSettings = SyncSettings(context)
        syncSettings.setPauseCondition(
            PauseCondition.Manual,
            nowMillis = PAUSE_SET_AT_WALL,
            nowElapsedRealtime = PAUSE_SET_AT,
        )
        val watcher = watcher(context)

        watcher.start(syncSettings, PauseCondition.Manual)

        assertEquals(PauseCondition.Manual, syncSettings.getPauseCondition())
    }

    // ---------------------------------------------------------------------------------------

    /** The one fixture every timing test starts from: the same pause, stored at the same instant,
     *  in both time domains. */
    private fun pausedSettings(context: Application): SyncSettings {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val syncSettings = SyncSettings(context)
        syncSettings.setFolderUri(Uri.parse("content://fake/tree/1"))
        syncSettings.setSyncMode(SyncMode.BATTERY_FRIENDLY)
        syncSettings.setPauseCondition(
            CONDITION,
            nowMillis = PAUSE_SET_AT_WALL,
            nowElapsedRealtime = PAUSE_SET_AT,
        )
        PeriodicSyncWorker.applySettings(context, syncSettings)
        return syncSettings
    }

    /** A watcher whose environment — process start, clock, and what is connected — is stated
     *  outright. None of these says anything about the CALLER; they are facts about the device. */
    private fun watcher(
        context: Application,
        processStart: Long = PROCESS_START,
        now: Long = PAUSE_SET_AT,
        connected: List<Network> = emptyList(),
    ): WifiResumeWatcher = WifiResumeWatcher(
        context = context,
        processStartElapsedRealtime = processStart,
        nowElapsedRealtime = { now },
        connectedNetworks = { connected },
    )

    private fun isPeriodicWorkActive(context: Application): Boolean {
        val infos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(PeriodicSyncWorker.WORK_NAME).get()
        return infos.isNotEmpty() && infos.any { it.state == WorkInfo.State.ENQUEUED }
    }

    private companion object {
        /** Fixed elapsedRealtime instants so "before"/"after" in these tests is exact. */
        const val PROCESS_START = 500_000L
        const val PAUSE_SET_AT = 560_000L
        const val REOPENED_AT = PAUSE_SET_AT + 30_000L

        /** The wall-clock stamp of the same moment — stored for display only, never compared. */
        const val PAUSE_SET_AT_WALL = 1_700_000_000_000L

        const val HOME_WIFI_NET_ID = 1
        val CONDITION = PauseCondition.SpecificWifi("HomeWifi")

        /** Exactly what `WifiInfo.getSSID()` hands back for [CONDITION]'s network. */
        const val REPORTED_SSID = "\"HomeWifi\""
    }
}
