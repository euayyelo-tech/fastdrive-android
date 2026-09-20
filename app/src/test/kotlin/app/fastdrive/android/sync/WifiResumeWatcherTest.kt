package app.fastdrive.android.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [matchesTargetNetwork] is the only part of Phase 4 Task 4's Wi-Fi resume watcher that's testable
 * as a pure function without a real network — [WifiResumeWatcher]'s
 * `ConnectivityManager.NetworkCallback` registration and real `WifiInfo`/permission behavior are
 * left for a real-device check, same as this task's brief calls for.
 */
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
}
