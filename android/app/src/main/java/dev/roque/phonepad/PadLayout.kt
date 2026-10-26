package dev.roque.phonepad

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import org.json.JSONObject

/** Un control colocable: posición y escala normalizadas (0..1). */
class PadItem(val id: String, fx: Float, fy: Float, scale: Float = 1f) {
    var fx by mutableFloatStateOf(fx)
    var fy by mutableFloatStateOf(fy)
    var scale by mutableFloatStateOf(scale)
}

class PadLayout(
    val items: SnapshotStateMap<String, PadItem> = mutableStateMapOf(),
) {
    fun item(id: String, dx: Float, dy: Float): PadItem =
        items.getOrPut(id) { PadItem(id, dx, dy) }

    fun reset() = items.clear()

    fun save(prefs: SharedPreferences) {
        val root = JSONObject()
        items.forEach { (id, item) ->
            root.put(
                id,
                JSONObject()
                    .put("x", item.fx.toDouble())
                    .put("y", item.fy.toDouble())
                    .put("s", item.scale.toDouble()),
            )
        }
        prefs.edit().putString(KEY, root.toString()).apply()
    }

    companion object {
        const val KEY = "layout_v1"

        fun load(prefs: SharedPreferences): PadLayout {
            val layout = PadLayout()
            val raw = prefs.getString(KEY, null) ?: return layout
            runCatching {
                val root = JSONObject(raw)
                root.keys().forEach { id ->
                    val saved = root.getJSONObject(id)
                    layout.items[id] = PadItem(
                        id,
                        saved.getDouble("x").toFloat(),
                        saved.getDouble("y").toFloat(),
                        saved.getDouble("s").toFloat(),
                    )
                }
            }
            return layout
        }
    }
}
