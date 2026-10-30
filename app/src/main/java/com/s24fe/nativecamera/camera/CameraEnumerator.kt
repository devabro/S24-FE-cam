package com.s24fe.nativecamera.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.DynamicRangeProfiles
import android.os.Build

/** One physical back camera, classified by focal length. Nothing is hardcoded to the S24 FE. */
data class LensInfo(
    val cameraId: String,
    val label: String,
    val focalLengthMm: Float,
    val supportsRaw: Boolean,
    val supports10Bit: Boolean
)

object CameraEnumerator {

    fun enumerateBackCameras(manager: CameraManager): List<LensInfo> {
        val found = mutableListOf<LensInfo>()
        for (id in manager.cameraIdList) {
            val chars = try {
                manager.getCameraCharacteristics(id)
            } catch (e: Exception) {
                continue
            }
            if (chars.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) {
                continue
            }
            val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.firstOrNull() ?: continue
            val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            val supportsRaw = caps.contains(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW
            )
            found.add(
                LensInfo(
                    cameraId = id,
                    label = "", // Filled in below once we know how many lenses exist.
                    focalLengthMm = focal,
                    supportsRaw = supportsRaw,
                    supports10Bit = supports10Bit(chars)
                )
            )
        }
        // Shortest focal length = ultrawide, longest = tele, middle = main.
        val sorted = found.sortedBy { it.focalLengthMm }
        return sorted.mapIndexed { index, lens ->
            val label = when {
                sorted.size >= 3 && index == 0 -> "Ultrawide"
                sorted.size >= 3 && index == sorted.lastIndex -> "Tele 3x"
                sorted.size >= 3 -> "Main"
                sorted.size == 2 && index == 0 -> "Wide"
                sorted.size == 2 -> "Tele"
                else -> "Lens ${index + 1}"
            }
            lens.copy(label = label)
        }
    }

    /**
     * 10-bit support, checked at runtime — never assumed.
     * API 31+: DYNAMIC_RANGE_TEN_BIT capability. API 33+: HLG10 dynamic range profile.
     */
    private fun supports10Bit(chars: CameraCharacteristics): Boolean {
        val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        if (caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT)) {
            return true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val profiles = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
            if (profiles != null && profiles.supportedProfiles.contains(DynamicRangeProfiles.HLG10)) {
                return true
            }
        }
        return false
    }
}
