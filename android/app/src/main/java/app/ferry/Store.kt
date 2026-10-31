package app.ferry

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** The Mac this phone is paired with. [host]/[port] are the last address that worked. */
data class PairedMac(val id: String, val name: String, val token: String, val host: String?, val port: Int) {
    fun toJson(): String = JSONObject()
        .put("id", id).put("name", name).put("token", token).put("host", host).put("port", port)
        .toString()

    companion object {
        fun fromJson(json: String): PairedMac? = runCatching {
            val o = JSONObject(json)
            PairedMac(o.getString("id"), o.getString("name"), o.getString("token"), o.optString("host").ifEmpty { null }, o.optInt("port"))
        }.getOrNull()
    }
}

object Store {
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences("ferry", Context.MODE_PRIVATE)
    }

    val deviceId: String
        get() = prefs.getString("deviceId", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("deviceId", it).apply()
        }

    var mac: PairedMac?
        get() = prefs.getString("mac", null)?.let(PairedMac::fromJson)
        set(value) {
            prefs.edit().apply { if (value == null) remove("mac") else putString("mac", value.toJson()) }.apply()
        }

    /** Recent transfers, newest first, so the list survives Ferry restarting. */
    var activity: List<Activity>
        get() = runCatching {
            val array = JSONArray(prefs.getString("activity", "[]"))
            List(array.length()) { i ->
                val o = array.getJSONObject(i)
                Activity(
                    incoming = o.getBoolean("incoming"),
                    title = o.getString("title"),
                    detail = o.optString("detail"),
                    uri = o.optString("uri").ifEmpty { null }?.let(Uri::parse),
                    time = o.getLong("time"),
                    text = if (o.has("text")) o.getString("text") else null,
                )
            }
        }.getOrDefault(emptyList())
        set(value) {
            val array = JSONArray()
            for (item in value) {
                array.put(
                    JSONObject().put("incoming", item.incoming).put("title", item.title).put("detail", item.detail)
                        .put("uri", item.uri?.toString()).put("time", item.time)
                        // Very long clipboard text isn't kept, so the preferences file stays small.
                        .put("text", item.text?.takeIf { it.length <= MAX_SAVED_TEXT })
                )
            }
            prefs.edit().putString("activity", array.toString()).apply()
        }

    private const val MAX_SAVED_TEXT = 20_000
}
