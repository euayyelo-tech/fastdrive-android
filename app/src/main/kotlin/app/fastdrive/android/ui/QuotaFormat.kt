package app.fastdrive.android.ui

/**
 * Formats a used/quota byte pair into a display string, e.g. "2.50 GB of 15.00 GB used".
 *
 * Deliberately matches the desktop client's own `bytes()` formatter (fastdrive-app's
 * desktop/src/renderer/index.html, used for that exact "X of Y" quota line in its tray UI) rather
 * than the spec's fallback fixed-decimal-GB convention: binary (1024-based) division with a unit
 * that scales with magnitude (B / KB / MB / GB), so the same number reads identically whether the
 * user checks their quota on desktop or on this Android app. Units are labeled "KB"/"MB"/"GB" (not
 * "KiB"/"MiB"/"GiB") to match that same desktop convention, even though the math is binary.
 */
fun formatQuota(usedBytes: Long, quotaBytes: Long): String {
    fun bytes(n: Long): String = when {
        n < 1_024L -> "$n B"
        n < 1_048_576L -> "%.0f KB".format(n / 1_024.0)
        n < 1_073_741_824L -> "%.1f MB".format(n / 1_048_576.0)
        else -> "%.2f GB".format(n / 1_073_741_824.0)
    }
    return "${bytes(usedBytes)} of ${bytes(quotaBytes)} used"
}
