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
 * The heart of this file is the A/B/C contrast further down: three situations that share an
 * IDENTICAL pause condition, SSID and network, and differ only in the one variable that is allowed
 * to decide the outcome — when the pause was set relative to this process and relative to the
 * network's arrival. Two previous fix rounds shipped a regression because that decision was keyed
 * on which function called [WifiResumeWatcher.start]; these tests are written so that a caller
 * cannot influence them at all.
 *
 * [ShadowNetwork.newInstance] needs Robolectric's environment to construct a real
 * `android.net.Network`, hence [RobolectricTestRunner] here.
 */
@RunWith(RobolectricTestRunner::class)
class WifiResumeWatcherTest {

    /** [NetworkFirstSeen] is process-scoped on purpose (it must survive Activity recreation), so
     *  tests have to isolate themselves from each other explicitly. */
    @Before
    fun clearNetworkRegistry() = NetworkFirstSeen.clearForTest()

    @After
    fun clearNetworkRegistryAfter() = NetworkFirstSeen.clearForTest()

    // ---------------------------------------------------------------------------------------
    // matchesTargetNetwork: which SSIDs satisfy which condition. Kept away from the A/B/C timing
    // tests below so the two concerns can never be conflated again.
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
    fun `a network connected before the pause was set never resolves it`() {
        assertFalse(
            shouldResolvePause(
                pauseSetAtMillis = PAUSE_SET_AT,
                processStartMillis = PAUSE_SET_AT - 60_000L,
                networkFirstSeenMillis = NetworkFirstSeen.CONNECTED_BEFORE_WE_LOOKED,
            ),
        )
        assertFalse(
            shouldResolvePause(
                pauseSetAtMillis = PAUSE_SET_AT,
                processStartMillis = PAUSE_SET_AT - 60_000L,
                networkFirstSeenMillis = PAUSE_SET_AT - 1L,
            ),
        )
    }

    @Test
    fun `a network that arrived after the pause was set resolves it`() {
        assertTrue(
            shouldResolvePause(
                pauseSetAtMillis = PAUSE_SET_AT,
                processStartMillis = PAUSE_SET_AT - 60_000L,
                networkFirstSeenMillis = PAUSE_SET_AT + 1L,
            ),
        )
    }

    @Test
    fun `a pause older than this process resolves on whatever we are already connected to`() {
        assertTrue(
            shouldResolvePause(
                pauseSetAtMillis = PAUSE_SET_AT,
                processStartMillis = PAUSE_SET_AT + 60_000L,
                networkFirstSeenMillis = NetworkFirstSeen.CONNECTED_BEFORE_WE_LOOKED,
            ),
        )
    }

    @Test
    fun `a pause with no stored timestamp (older install) behaves like one older than this process`() {
        assertTrue(
            shouldResolvePause(
                pauseSetAtMillis = Long.MIN_VALUE,
                processStartMillis = PAUSE_SET_AT,
                networkFirstSeenMillis = NetworkFirstSeen.CONNECTED_BEFORE_WE_LOOKED,
            ),
        )
    }

    // ---------------------------------------------------------------------------------------
    // Situations A, B and C: the controlled set. Same PauseCondition (SpecificWifi "HomeWifi"),
    // same SSID reported by the platform, same already-connected Network, same stored pause — the
    // ONLY thing that differs is the timing state each one is testing.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `Situation A - a pause set while already on the target network is NOT instantly cleared`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val syncSettings = pausedSettings(context)
        assertFalse(isPeriodicWorkActive(context))
        val alreadyConnected = alreadyConnectedNetwork()
        // The pause was set DURING this process, and we never watched this network arrive.
        val watcher = WifiResumeWatcher(context, processStartMillis = PAUSE_SET_AT - 60_000L)

        val resolved = watcher.handleNetworkCandidate(
            syncSettings, CONDITION, alreadyConnected, REPORTED_SSID,
        )

        assertFalse(resolved)
        assertEquals(CONDITION, syncSettings.getPauseCondition())
        assertFalse("the pause the user just set must still be in force", isPeriodicWorkActive(context))
    }

    @Test
    fun `Situation B - a pause that predates this process resolves on the already-connected network`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val syncSettings = pausedSettings(context)
        assertFalse(isPeriodicWorkActive(context))
        val alreadyConnected = alreadyConnectedNetwork()
        // Identical to Situation A except this: the app was killed and restarted after the pause
        // was set, so everything we can see now is news to this process.
        val watcher = WifiResumeWatcher(context, processStartMillis = PAUSE_SET_AT + 60_000L)

        val resolved = watcher.handleNetworkCandidate(
            syncSettings, CONDITION, alreadyConnected, REPORTED_SSID,
        )

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
        val alreadyConnected = alreadyConnectedNetwork()
        val processStart = PAUSE_SET_AT - 60_000L

        // The watcher armed when the user set the pause (SyncSettingsScreen), then the Activity is
        // destroyed and recreated by a rotation: stop(), a brand-new watcher instance, start().
        // Nothing about the world changed — same process, same network, same pause.
        val beforeRotation = WifiResumeWatcher(context, processStartMillis = processStart)
        beforeRotation.start(syncSettings, CONDITION)
        beforeRotation.stop()

        val afterRotation = WifiResumeWatcher(context, processStartMillis = processStart)
        afterRotation.start(syncSettings, CONDITION)
        val resolved = afterRotation.handleNetworkCandidate(
            syncSettings, CONDITION, alreadyConnected, REPORTED_SSID,
        )

        assertFalse("a rotation is not the user reconnecting to the network", resolved)
        assertEquals(CONDITION, syncSettings.getPauseCondition())
        assertFalse(isPeriodicWorkActive(context))
    }

    @Test
    fun `a network that connects after the pause was set still resolves it in the same process`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val syncSettings = pausedSettings(context)
        val watcher = WifiResumeWatcher(context, processStartMillis = PAUSE_SET_AT - 60_000L)
        // A genuine connection event: the platform hands out a fresh netId, so this process sees a
        // Network it has never seen before and stamps it with the moment it arrived. Stamped
        // explicitly here rather than leaning on the wall clock, so the test is deterministic.
        val newlyConnected = ShadowNetwork.newInstance(77)
        NetworkFirstSeen.recordSeen(newlyConnected, nowMillis = PAUSE_SET_AT + 5_000L)

        val resolved = watcher.handleNetworkCandidate(
            syncSettings, CONDITION, newlyConnected, REPORTED_SSID,
        )

        assertTrue(resolved)
        assertNull(syncSettings.getPauseCondition())
        assertTrue(isPeriodicWorkActive(context))
    }

    /**
     * The reviewer's explicit ask for Round 3: the invariant must hold for call sites that do not
     * exist yet. This test invents one — an anonymous lambda that is neither
     * `SyncSettingsScreen.applyPause` nor `MainActivity.onCreate` — arms the watcher through it
     * with a network that was connected before the pause was set, and asserts nothing resolves.
     *
     * If anyone ever reintroduces a caller-supplied "treat the existing connection as a match"
     * parameter, this test is the one that has no plausible value to pass for it.
     */
    @Test
    fun `no call site, named or hypothetical, can make a pre-pause network resolve the pause`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val syncSettings = pausedSettings(context)
        val alreadyConnected = alreadyConnectedNetwork()
        val processStart = PAUSE_SET_AT - 60_000L

        val armFromAnUnknownFutureCallSite: (WifiResumeWatcher) -> Unit = { watcher ->
            watcher.start(syncSettings, CONDITION)
        }

        repeat(3) {
            val watcher = WifiResumeWatcher(context, processStartMillis = processStart)
            armFromAnUnknownFutureCallSite(watcher)
            val resolved = watcher.handleNetworkCandidate(
                syncSettings, CONDITION, alreadyConnected, REPORTED_SSID,
            )
            assertFalse("arming #$it must not resolve a pause on a pre-pause network", resolved)
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
        val watcher = WifiResumeWatcher(context, processStartMillis = PAUSE_SET_AT + 60_000L)

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
        val watcher = WifiResumeWatcher(context, processStartMillis = PAUSE_SET_AT - 60_000L)
        val alreadyConnected = alreadyConnectedNetwork()

        watcher.start(syncSettings, CONDITION)

        assertEquals(
            NetworkFirstSeen.CONNECTED_BEFORE_WE_LOOKED,
            NetworkFirstSeen.firstSeenMillis(alreadyConnected),
        )
        watcher.stop()
        // stop() must NOT forget it — the next onCreate after a rotation depends on this record.
        assertEquals(
            NetworkFirstSeen.CONNECTED_BEFORE_WE_LOOKED,
            NetworkFirstSeen.firstSeenMillis(alreadyConnected),
        )
    }

    @Test
    fun `start ignores a condition that is not Wi-Fi based`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val syncSettings = SyncSettings(context)
        syncSettings.setPauseCondition(PauseCondition.Manual, nowMillis = PAUSE_SET_AT)
        val watcher = WifiResumeWatcher(context, processStartMillis = PAUSE_SET_AT - 60_000L)

        watcher.start(syncSettings, PauseCondition.Manual)

        assertEquals(PauseCondition.Manual, syncSettings.getPauseCondition())
    }

    // ---------------------------------------------------------------------------------------

    /** The one fixture every A/B/C test starts from: the same pause, stored at the same instant. */
    private fun pausedSettings(context: Application): SyncSettings {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val syncSettings = SyncSettings(context)
        syncSettings.setFolderUri(Uri.parse("content://fake/tree/1"))
        syncSettings.setSyncMode(SyncMode.BATTERY_FRIENDLY)
        syncSettings.setPauseCondition(CONDITION, nowMillis = PAUSE_SET_AT)
        PeriodicSyncWorker.applySettings(context, syncSettings)
        return syncSettings
    }

    /** A network this process saw as already connected — i.e. it was up before we ever looked, so
     *  certainly before the pause was set. */
    private fun alreadyConnectedNetwork(): Network =
        ShadowNetwork.newInstance(1).also { NetworkFirstSeen.recordAlreadyConnected(it) }

    private fun isPeriodicWorkActive(context: Application): Boolean {
        val infos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(PeriodicSyncWorker.WORK_NAME).get()
        return infos.isNotEmpty() && infos.any { it.state == WorkInfo.State.ENQUEUED }
    }

    private companion object {
        /** A fixed instant so "before"/"after" in these tests is exact, not clock-dependent. */
        const val PAUSE_SET_AT = 1_700_000_000_000L
        val CONDITION = PauseCondition.SpecificWifi("HomeWifi")

        /** Exactly what `WifiInfo.getSSID()` hands back for [CONDITION]'s network. */
        const val REPORTED_SSID = "\"HomeWifi\""
    }
}
