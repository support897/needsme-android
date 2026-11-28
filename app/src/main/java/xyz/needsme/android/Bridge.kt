package xyz.needsme.android

import android.content.Context
import androidx.work.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/** Alerts that arrive even when the app is closed.
 *  AIs (Polar, Claude, ChatGPT...) tell the NeedsMe bridge "needs you" or "finished" through their MCP link.
 *  The bridge keeps the event. This phone picks it up every 15 minutes (and every 20 seconds while the app is open)
 *  and shows it. No AI key and no chat text is stored on the bridge. */
object Bridge {
    const val BASE = "https://billowing-lake-5abc.placenciailse.workers.dev"
    private fun prefs(c: Context) = c.getSharedPreferences("nm_bridge", Context.MODE_PRIVATE)
    fun token(c: Context): String? = prefs(c).getString("t", null)

    private fun call(method: String, path: String, bearer: String, body: String? = null): Pair<Int, String> {
        val h = URL(BASE + path).openConnection() as HttpURLConnection
        h.requestMethod = method; h.connectTimeout = 15000; h.readTimeout = 20000
        h.setRequestProperty("Authorization", "Bearer $bearer"); h.setRequestProperty("Content-Type", "application/json")
        if (body != null) { h.doOutput = true; h.outputStream.use { it.write(body.toByteArray()) } }
        val code = h.responseCode
        val txt = (if (code < 400) h.inputStream else h.errorStream)?.bufferedReader()?.readText() ?: ""
        return code to txt
    }

    private fun idToken(u: User): String? = try {
        val h = URL("https://securetoken.googleapis.com/v1/token?key=${u.apiKey}").openConnection() as HttpURLConnection
        h.requestMethod = "POST"; h.doOutput = true; h.connectTimeout = 15000; h.readTimeout = 20000
        h.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        h.outputStream.use { it.write("grant_type=refresh_token&refresh_token=${java.net.URLEncoder.encode(u.refresh, "UTF-8")}".toByteArray()) }
        JSONObject(h.inputStream.bufferedReader().readText()).optString("id_token").ifBlank { null }
    } catch (e: Exception) { null }

    /** Makes this phone's own private bridge token once (named "NeedsMe Android"). */
    fun ensureLink(c: Context, u: User) {
        if (token(c) != null) return
        val id = idToken(u) ?: return
        try {
            val (s, b) = call("POST", "/tokens", id, """{"name":"NeedsMe Android"}""")
            if (s == 200) prefs(c).edit().putString("t", JSONObject(b).getString("token")).putString("since", java.time.Instant.now().toString()).apply()
        } catch (_: Exception) {}
    }

    /** One event from the bridge -> (title, body). Title says which AI; body is the AI's own words. Pure, so it is unit tested. */
    fun alertFor(e: JSONObject): Pair<String, String>? {
        val ai = e.optString("ai").ifBlank { "An AI" }
        val needs = e.optString("needs_you")
        return when {
            needs.isNotBlank() -> "$ai needs you" to needs
            e.optBoolean("finished") -> "$ai finished" to e.optString("summary").ifBlank { e.optString("project").ifBlank { "A job is done" } }
            else -> null
        }
    }

    /** Fetches events, shows each NEW one as a phone alert. Every device keeps its own "last seen" time, so a phone and a computer both get every alert. Returns how many were shown. */
    fun poll(c: Context): Int {
        val t = token(c) ?: return 0
        return try {
            val (s, b) = call("GET", "/events", t)
            if (s == 401) { prefs(c).edit().remove("t").remove("since").apply(); return 0 }
            if (s != 200) return 0
            val arr = JSONArray(b)
            val since = prefs(c).getString("since", null)
            var newest = since ?: java.time.Instant.now().toString(); var shown = 0
            if (since != null) for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i); val at = e.optString("at")
                if (at > since) { alertFor(e)?.let { (title, body) -> Notify.postRaw(c, e.optString("id"), title, body); shown++ }; if (at > newest) newest = at }
            }
            prefs(c).edit().putString("since", newest).apply()
            shown
        } catch (_: Exception) { 0 }
    }

    /** Checks every 15 minutes, even when the app is closed (Android's shortest repeat time). */
    fun schedule(c: Context) {
        val req = PeriodicWorkRequestBuilder<BridgeWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(c).enqueueUniquePeriodicWork("nm_bridge_poll", ExistingPeriodicWorkPolicy.KEEP, req)
    }
}

class BridgeWorker(ctx: Context, p: WorkerParameters) : Worker(ctx, p) {
    override fun doWork(): Result { Bridge.poll(applicationContext); return Result.success() }
}
