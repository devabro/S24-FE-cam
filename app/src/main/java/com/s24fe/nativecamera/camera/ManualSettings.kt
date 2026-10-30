package com.s24fe.nativecamera.camera

import android.hardware.camera2.CaptureRequest

/**
 * Everything the user can tweak. Kept as plain data so presets are just this
 * object saved to disk — no extra mapping layer.
 */
data class ManualSettings(
    // Index into ISO_VALUES. -1 means "leave it on auto".
    var isoIndex: Int = -1,
    // Index into SHUTTER_VALUES_NS. -1 means "leave it on auto".
    var shutterIndex: Int = -1,
    // One of CaptureRequest.CONTROL_AWB_MODE_*.
    var awbMode: Int = CaptureRequest.CONTROL_AWB_MODE_AUTO,
    // 0..100. -1 means autofocus. 0 = infinity, 100 = closest focus.
    var focusProgress: Int = -1,
    // N8-style: turn Samsung-style sharpening / noise smoothing off.
    var naturalOn: Boolean = false,
    // 10-bit HLG video. Only offered when the device reports support.
    var logOn: Boolean = false,
    // Zebra threshold in luminance (0..255).
    var zebraThreshold: Int = 235
) {
    companion object {
        val ISO_VALUES = intArrayOf(100, 200, 400, 800, 1600, 3200, 6400)

        // Exposure times in nanoseconds, fastest first.
        val SHUTTER_VALUES_NS = longArrayOf(
            125_000L,      // 1/8000
            250_000L,      // 1/4000
            500_000L,      // 1/2000
            1_000_000L,    // 1/1000
            2_000_000L,    // 1/500
            4_000_000L,    // 1/250
            8_000_000L,    // 1/125
            16_666_666L,   // 1/60
            33_333_333L,   // 1/30
            66_666_666L,   // 1/15
            125_000_000L,  // 1/8
            250_000_000L,  // 1/4
            500_000_000L,  // 1/2
            1_000_000_000L // 1s
        )

        fun shutterLabel(ns: Long): String {
            return if (ns >= 1_000_000_000L) {
                "${ns / 1_000_000_000L}s"
            } else {
                "1/${1_000_000_000L / ns}"
            }
        }
    }

    val isoValue: Int? get() = if (isoIndex in ISO_VALUES.indices) ISO_VALUES[isoIndex] else null
    val shutterNs: Long? get() = if (shutterIndex in SHUTTER_VALUES_NS.indices) SHUTTER_VALUES_NS[shutterIndex] else null
    val isManualExposure: Boolean get() = isoValue != null || shutterNs != null
    val isManualFocus: Boolean get() = focusProgress >= 0
}
