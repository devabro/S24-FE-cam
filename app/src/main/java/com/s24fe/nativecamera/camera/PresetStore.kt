package com.s24fe.nativecamera.camera

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Named presets in one local JSON file. No database, no cloud, no account.
 * A preset stores manual settings + toggles + the lens it was saved on.
 */
data class Preset(
    val name: String,
    val settings: ManualSettings,
    val lensId: String
)

object PresetStore {
    private const val FILE_NAME = "presets.json"

    fun loadAll(context: Context): List<Preset> {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return emptyList()
        return try {
            val array = JSONArray(file.readText())
            List(array.length()) { i ->
                val obj = array.getJSONObject(i)
                val s = obj.getJSONObject("settings")
                Preset(
                    name = obj.getString("name"),
                    settings = ManualSettings(
                        isoIndex = s.getInt("isoIndex"),
                        shutterIndex = s.getInt("shutterIndex"),
                        awbMode = s.getInt("awbMode"),
                        focusProgress = s.getInt("focusProgress"),
                        naturalOn = s.getBoolean("naturalOn"),
                        logOn = s.getBoolean("logOn"),
                        zebraThreshold = s.getInt("zebraThreshold")
                    ),
                    lensId = obj.getString("lensId")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(context: Context, preset: Preset) {
        val current = loadAll(context).filter { it.name != preset.name } + preset
        val array = JSONArray()
        for (p in current) {
            val s = JSONObject().apply {
                put("isoIndex", p.settings.isoIndex)
                put("shutterIndex", p.settings.shutterIndex)
                put("awbMode", p.settings.awbMode)
                put("focusProgress", p.settings.focusProgress)
                put("naturalOn", p.settings.naturalOn)
                put("logOn", p.settings.logOn)
                put("zebraThreshold", p.settings.zebraThreshold)
            }
            array.put(
                JSONObject().apply {
                    put("name", p.name)
                    put("settings", s)
                    put("lensId", p.lensId)
                }
            )
        }
        File(context.filesDir, FILE_NAME).writeText(array.toString())
    }
}
