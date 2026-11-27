package xyz.needsme.android

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Saves the list of AIs (names, emoji, notes) to the same Needs Me account the Mac app uses.
 * AI passwords and cookies are never part of it.
 */
object Sync {
    private const val PROJECT = "needs-me-480ea"
    private fun call(method: String, url: String, body: String? = null, bearer: String? = null, form: Boolean = false): String {
        val c = URL(url).openConnection() as HttpURLConnection
        // Google APIs accept POST + X-HTTP-Method-Override, which works on every Java/Android HTTP stack (plain Java has no PATCH).
        if (method == "PATCH") { c.requestMethod = "POST"; c.setRequestProperty("X-HTTP-Method-Override", "PATCH") } else c.requestMethod = method
        c.connectTimeout = 15000; c.readTimeout = 20000
        bearer?.let { c.setRequestProperty("Authorization", "Bearer $it") }
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", if (form) "application/x-www-form-urlencoded" else "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
        if (code !in 200..299) throw RuntimeException("HTTP $code")
        return text
    }
    fun idToken(apiKey: String, refresh: String): String =
        JSONObject(call("POST", "https://securetoken.googleapis.com/v1/token?key=$apiKey", "grant_type=refresh_token&refresh_token=$refresh", form = true)).getString("id_token")

    private fun docUrl(uid: String) = "https://firestore.googleapis.com/v1/projects/$PROJECT/databases/(default)/documents/users/$uid/settings/android"

    fun push(apiKey: String, refresh: String, uid: String, json: String) {
        val t = idToken(apiKey, refresh)
        val body = JSONObject().put("fields", JSONObject()
            .put("json", JSONObject().put("stringValue", json))
            .put("updated", JSONObject().put("integerValue", System.currentTimeMillis().toString()))
            .put("device", JSONObject().put("stringValue", "android"))).toString()
        call("PATCH", docUrl(uid) + "?updateMask.fieldPaths=json&updateMask.fieldPaths=updated&updateMask.fieldPaths=device", body, t)
    }
    private fun url(uid: String, doc: String) = "https://firestore.googleapis.com/v1/projects/$PROJECT/databases/(default)/documents/users/$uid/settings/$doc"
    /** Reads one settings document. null = it does not exist yet. Throws on other errors (for example "HTTP 429" when the daily quota is used up). */
    fun readDoc(token: String, uid: String, doc: String): String? {
        val c = URL(url(uid, doc)).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 20000; c.setRequestProperty("Authorization", "Bearer $token")
        val code = c.responseCode
        if (code == 404) return null
        if (code !in 200..299) throw RuntimeException("HTTP $code")
        val f = JSONObject(c.inputStream.bufferedReader().readText()).optJSONObject("fields") ?: return ""
        return f.optJSONObject("json")?.optString("stringValue") ?: ""
    }
    fun writeShared(token: String, uid: String, list: List<Shared>) {
        val body = JSONObject().put("fields", JSONObject()
            .put("json", JSONObject().put("stringValue", SharedList.encode(list)))
            .put("updated", JSONObject().put("integerValue", System.currentTimeMillis().toString()))
            .put("device", JSONObject().put("stringValue", "android"))).toString()
        call("PATCH", url(uid, SharedList.DOC) + "?updateMask.fieldPaths=json&updateMask.fieldPaths=updated&updateMask.fieldPaths=device", body, token)
    }
    /** One round: read the shared list (the first time, bring in the old phone list too), merge with this phone, write back if anything is newer here. */
    fun sharedRound(apiKey: String, refresh: String, uid: String, local: List<Shared>): List<Shared> {
        val t = idToken(apiKey, refresh)
        val remote = readDoc(t, uid, SharedList.DOC)
        var theirs = SharedList.parse(remote)
        if (remote == null) theirs = SharedList.merge(theirs, SharedList.fromLegacy(try { readDoc(t, uid, "android") } catch (e: Exception) { null }))
        val merged = SharedList.merge(local, theirs)
        if (remote == null || !SharedList.same(merged, theirs)) writeShared(t, uid, merged)
        return merged
    }
    fun pull(apiKey: String, refresh: String, uid: String): String? = try {
        val t = idToken(apiKey, refresh)
        JSONObject(call("GET", docUrl(uid), bearer = t)).getJSONObject("fields").getJSONObject("json").getString("stringValue")
    } catch (e: Exception) { null }
}
