package app.fastdrive.android.sync

import org.junit.Assert.assertFalse
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
}
