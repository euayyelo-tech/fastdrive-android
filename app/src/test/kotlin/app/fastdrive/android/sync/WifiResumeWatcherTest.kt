package app.fastdrive.android.sync

import android.app.Application
import android.net.ConnectivityManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowNetwork

/**
 * [matchesTargetNetwork] and [isPreExistingConnection] are the parts of Phase 4 Task 4's Wi-Fi
 * resume watcher that are testable as pure functions without a real network —
 * [WifiResumeWatcher]'s `ConnectivityManager.NetworkCallback` registration and real
 * `WifiInfo`/permission behavior are left for a real-device check, same as this task's brief calls
 * for. [ShadowNetwork.newInstance] needs Robolectric's environment to construct a real
 * `android.net.Network`, hence [RobolectricTestRunner] here (unlike the plain-JUnit tests below it
 * used to be able to run without one).
 */
@RunWith(RobolectricTestRunner::class)
class WifiResumeWatcherTest {

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

    // Finding #1 (Phase 4 fix round): registerNetworkCallback() delivers an immediate callback for
    // any network that already satisfies the request at registration time. isPreExistingConnection
    // is what tells that synchronous "already connected" delivery apart from a real new connection.

    @Test
    fun `the network active before registration is a pre-existing connection`() {
        val network = ShadowNetwork.newInstance(1)
        val sameNetwork = ShadowNetwork.newInstance(1)
        assertTrue(isPreExistingConnection(network, sameNetwork))
    }

    @Test
    fun `a different network id is a new connection, not pre-existing`() {
        val network = ShadowNetwork.newInstance(2)
        val previouslyActive = ShadowNetwork.newInstance(1)
        assertFalse(isPreExistingConnection(network, previouslyActive))
    }

    @Test
    fun `no network active before registration means nothing is pre-existing`() {
        val network = ShadowNetwork.newInstance(1)
        assertFalse(isPreExistingConnection(network, null))
    }

    // Round 2, Bug 2: "already on this network at registration time" means two OPPOSITE things
    // depending on which call site armed the watcher, so both get an explicit test — from the same
    // starting state (a Wi-Fi pause stored, the device already on the matching network), to stop
    // the two from flip-flopping again.

    private fun pausedSettings(context: Application, condition: PauseCondition): SyncSettings {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val syncSettings = SyncSettings(context)
        syncSettings.setFolderUri(Uri.parse("content://fake/tree/1"))
        syncSettings.setSyncMode(SyncMode.BATTERY_FRIENDLY)
        syncSettings.setPauseCondition(condition)
        PeriodicSyncWorker.applySettings(context, syncSettings)
        return syncSettings
    }

    private fun isPeriodicWorkActive(context: Application): Boolean {
        val infos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(PeriodicSyncWorker.WORK_NAME).get()
        return infos.isNotEmpty() && infos.any { it.state == WorkInfo.State.ENQUEUED }
    }

    @Test
    fun `Situation A - a pause set while already on the target network is NOT instantly cleared`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val syncSettings = pausedSettings(context, PauseCondition.AnyWifi)
        assertFalse(isPeriodicWorkActive(context))
        val watcher = WifiResumeWatcher(context)
        val alreadyConnected = ShadowNetwork.newInstance(1)

        // Exactly what SyncSettingsScreen.applyPause's registration delivers synchronously: the
        // network that was already active, which start(evaluateExistingConnection = false)
        // captured as the baseline to ignore.
        val resolved = watcher.handleNetworkCandidate(
            syncSettings,
            PauseCondition.AnyWifi,
            network = alreadyConnected,
            baselineNetwork = alreadyConnected,
            connectedSsid = "HomeWifi",
        )

        assertFalse(resolved)
        assertEquals(PauseCondition.AnyWifi, syncSettings.getPauseCondition())
        assertFalse("the pause the user just set must still be in force", isPeriodicWorkActive(context))
    }

    @Test
    fun `Situation B - re-arming an existing pause while already on the target network resolves it`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val syncSettings = pausedSettings(context, PauseCondition.SpecificWifi("HomeWifi"))
        assertFalse(isPeriodicWorkActive(context))
        val watcher = WifiResumeWatcher(context)
        val alreadyConnected = ShadowNetwork.newInstance(1)

        // MainActivity.onCreate's re-arm passes evaluateExistingConnection = true, i.e. no
        // baseline to ignore — so the very same delivery as Situation A must resolve the pause.
        val resolved = watcher.handleNetworkCandidate(
            syncSettings,
            PauseCondition.SpecificWifi("HomeWifi"),
            network = alreadyConnected,
            baselineNetwork = null,
            connectedSsid = "\"HomeWifi\"",
        )

        assertTrue(resolved)
        assertNull(syncSettings.getPauseCondition())
        assertTrue(
            "yesterday's Wi-Fi pause must actually restart sync, not wait for a reconnection",
            isPeriodicWorkActive(context),
        )
    }

    @Test
    fun `a non-matching network never resolves the pause in either mode`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val syncSettings = pausedSettings(context, PauseCondition.SpecificWifi("HomeWifi"))
        val watcher = WifiResumeWatcher(context)

        val resolved = watcher.handleNetworkCandidate(
            syncSettings,
            PauseCondition.SpecificWifi("HomeWifi"),
            network = ShadowNetwork.newInstance(2),
            baselineNetwork = null,
            connectedSsid = "CoffeeShopWifi",
        )

        assertFalse(resolved)
        assertEquals(PauseCondition.SpecificWifi("HomeWifi"), syncSettings.getPauseCondition())
    }

    @Test
    fun `start captures a baseline to ignore only when not evaluating the existing connection`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val syncSettings = pausedSettings(context, PauseCondition.AnyWifi)
        val activeNetwork = context.getSystemService(ConnectivityManager::class.java).activeNetwork
        val watcher = WifiResumeWatcher(context)

        // MainActivity's startup re-arm: nothing is ignored, so an already-matching network
        // resolves the pause (Situation B above proves what that then does).
        watcher.start(syncSettings, PauseCondition.AnyWifi, evaluateExistingConnection = true)
        assertNull(watcher.ignoredBaselineNetwork)
        watcher.stop()

        // SyncSettingsScreen.applyPause: whatever the device is on right now is ignored
        // (Situation A above proves what that then does).
        watcher.start(syncSettings, PauseCondition.AnyWifi, evaluateExistingConnection = false)
        assertEquals(activeNetwork, watcher.ignoredBaselineNetwork)
        watcher.stop()
    }

    @Test
    fun `start ignores a condition that is not Wi-Fi based`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val syncSettings = pausedSettings(context, PauseCondition.Manual)
        val watcher = WifiResumeWatcher(context)

        watcher.start(syncSettings, PauseCondition.Manual, evaluateExistingConnection = true)

        assertNull(watcher.ignoredBaselineNetwork)
        assertEquals(PauseCondition.Manual, syncSettings.getPauseCondition())
    }
}
