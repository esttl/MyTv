package app.mytv.player

import android.content.Context

/** Ajustes guardados solo en el dispositivo. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("mitv", Context.MODE_PRIVATE)

    var server: String
        get() = sp.getString("server", "") ?: ""
        set(v) = sp.edit().putString("server", v).apply()

    var user: String
        get() = sp.getString("user", "") ?: ""
        set(v) = sp.edit().putString("user", v).apply()

    var pass: String
        get() = sp.getString("pass", "") ?: ""
        set(v) = sp.edit().putString("pass", v).apply()

    /** "m3u8" (HLS) o "ts" */
    var format: String
        get() = sp.getString("format", "m3u8") ?: "m3u8"
        set(v) = sp.edit().putString("format", v).apply()

    /** 0 = normal, 1 = grande, 2 = máximo */
    var bufferPreset: Int
        get() = sp.getInt("buffer", 1)
        set(v) = sp.edit().putInt("buffer", v).apply()

    var showStats: Boolean
        get() = sp.getBoolean("stats", false)
        set(v) = sp.edit().putBoolean("stats", v).apply()

    /** 0 = ajustar, 1 = zoom, 2 = llenar */
    var resizeMode: Int
        get() = sp.getInt("resize", 0)
        set(v) = sp.edit().putInt("resize", v).apply()

    val hasAccount: Boolean
        get() = server.isNotBlank() && user.isNotBlank() && pass.isNotBlank()

    fun clearAccount() {
        sp.edit().remove("server").remove("user").remove("pass").apply()
    }
}
