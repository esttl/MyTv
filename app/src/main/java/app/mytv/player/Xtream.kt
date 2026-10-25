package app.mytv.player

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object Http {
    const val USER_AGENT = "VLC/3.0.20 LibVLC/3.0.20"

    /** Cliente para los streams de video. */
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()

    /** Cliente para la API (la lista de canales puede ser grande y lenta). */
    val api: OkHttpClient = client.newBuilder()
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
}

/** Cliente mínimo de la API Xtream Codes (player_api.php). */
class XtreamApi(server: String, private val user: String, private val pass: String) {

    val base: String = normalize(server)

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun fetch(action: String? = null): String {
        var url = "$base/player_api.php?username=${enc(user)}&password=${enc(pass)}"
        if (action != null) url += "&action=$action"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", Http.USER_AGENT)
            .build()
        Http.api.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("El servidor respondió HTTP ${response.code}")
            return response.body?.string() ?: error("Respuesta vacía del servidor")
        }
    }

    fun login(): AccountInfo {
        val json = JSONObject(fetch())
        val info = json.optJSONObject("user_info") ?: error("Respuesta inesperada del servidor")
        if (info.optString("auth") != "1") error("Usuario o contraseña incorrectos")
        val expiry = info.optString("exp_date").toLongOrNull()?.let {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(it * 1000))
        }
        return AccountInfo(
            status = info.optString("status"),
            expiry = expiry,
            activeConnections = info.optString("active_cons"),
            maxConnections = info.optString("max_connections")
        )
    }

    fun categories(): List<Category> {
        val arr = JSONArray(fetch("get_live_categories"))
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            Category(o.optString("category_id"), o.optString("category_name"))
        }
    }

    fun channels(): List<Channel> {
        val arr = JSONArray(fetch("get_live_streams"))
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            Channel(o.optString("stream_id"), o.optString("name").trim(), o.optString("category_id"))
        }
    }

    fun streamUrl(streamId: String, ext: String): String =
        "$base/live/${enc(user)}/${enc(pass)}/$streamId.$ext"

    companion object {
        fun normalize(server: String): String {
            var s = server.trim().trimEnd('/')
            if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
            return s
        }
    }
}
