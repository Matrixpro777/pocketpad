package dev.roque.pocketpad

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import org.json.JSONObject

/** Un control colocable: posición y escala normalizadas (0..1). */
class PadItem(val id: String, fx: Float, fy: Float, scale: Float = 1f) {
    var fx by mutableFloatStateOf(fx)
    var fy by mutableFloatStateOf(fy)
    var scale by mutableFloatStateOf(scale)
}

/**
 * Layouts por juego. Cada perfil guarda la posición y escala de cada control; el
 * perfil activo se recuerda entre sesiones. Clave `layout_v2`; el formato viejo
 * (`layout_v1`) se migra al perfil «por defecto» la primera vez.
 */
class PadLayout(private val prefs: SharedPreferences) {

    val items: SnapshotStateMap<String, PadItem> = mutableStateMapOf()

    var profile by mutableStateOf(DEFAULT)
        private set

    var profileNames by mutableStateOf(listOf(DEFAULT))
        private set

    init {
        migrateIfNeeded()
        profileNames = readNames()
        profile = prefs.getString(KEY_CURRENT, DEFAULT)?.takeIf { it in profileNames } ?: DEFAULT
        loadItems()
    }

    fun item(id: String, dx: Float, dy: Float): PadItem =
        items.getOrPut(id) { PadItem(id, dx, dy) }

    /** Restablece el perfil activo a las posiciones de fábrica. */
    fun reset() {
        items.clear()
        save()
    }

    fun save() {
        val root = readRoot()
        val profiles = root.optJSONObject("profiles") ?: JSONObject()
        profiles.put(profile, encodeItems())
        root.put("profiles", profiles)
        root.put("current", profile)
        prefs.edit().putString(KEY, root.toString()).apply()
    }

    fun switchTo(name: String) {
        if (name == profile || name !in profileNames) return
        save()
        profile = name
        prefs.edit().putString(KEY_CURRENT, name).apply()
        items.clear()
        loadItems()
    }

    /** Crea un perfil vacío (posiciones de fábrica) y lo activa. */
    fun create(name: String): Boolean {
        val clean = name.trim().take(24)
        if (clean.isEmpty() || clean in profileNames) return false
        save()
        profileNames = profileNames + clean
        profile = clean
        items.clear()
        save()
        return true
    }

    /** Borra un perfil; si era el activo, vuelve a «por defecto». */
    fun delete(name: String) {
        if (name == DEFAULT) return
        val root = readRoot()
        root.optJSONObject("profiles")?.remove(name)
        root.put("current", if (name == profile) DEFAULT else profile)
        prefs.edit().putString(KEY, root.toString()).apply()
        profileNames = readNames()
        if (name == profile) {
            profile = DEFAULT
            items.clear()
            loadItems()
        }
    }

    // -- persistencia -------------------------------------------------
    private fun encodeItems(): JSONObject {
        val out = JSONObject()
        items.forEach { (id, item) ->
            out.put(
                id,
                JSONObject()
                    .put("x", item.fx.toDouble())
                    .put("y", item.fy.toDouble())
                    .put("s", item.scale.toDouble()),
            )
        }
        return out
    }

    private fun loadItems() {
        val saved = readRoot().optJSONObject("profiles")?.optJSONObject(profile) ?: return
        saved.keys().forEach { id ->
            val entry = saved.getJSONObject(id)
            items[id] = PadItem(
                id,
                entry.getDouble("x").toFloat(),
                entry.getDouble("y").toFloat(),
                entry.getDouble("s").toFloat(),
            )
        }
    }

    private fun readRoot(): JSONObject = runCatching {
        JSONObject(prefs.getString(KEY, null) ?: "{}")
    }.getOrDefault(JSONObject())

    private fun readNames(): List<String> {
        val stored = readRoot().optJSONObject("profiles") ?: return listOf(DEFAULT)
        val names = mutableListOf(DEFAULT)
        stored.keys().forEach { name ->
            if (name != DEFAULT && name !in names) names += name
        }
        return names
    }

    private fun migrateIfNeeded() {
        if (prefs.contains(KEY)) return
        val old = prefs.getString("layout_v1", null) ?: return
        runCatching {
            val root = JSONObject()
            root.put("current", DEFAULT)
            root.put("profiles", JSONObject().put(DEFAULT, JSONObject(old)))
            prefs.edit().putString(KEY, root.toString()).apply()
        }
    }

    companion object {
        const val KEY = "layout_v2"
        const val KEY_CURRENT = "layout_current"
        const val DEFAULT = "por defecto"
    }
}
