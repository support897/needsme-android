package xyz.needsme.android

import org.json.JSONArray
import org.json.JSONObject

/**
 * The shared AI list: one list per Needs Me account, the same on phone, web and every computer.
 * Firestore users/{uid}/settings/aiaccounts, field json = {"v":1,"accounts":[...]}.
 * Same shape as nm-shared.js in the Mac and Windows app. AI logins and cookies are never part of it.
 */
data class Shared(
    val id: String, val ai: String, val aiName: String, val url: String, val name: String, val desc: String,
    val emoji: String, val img: String, val color: String, val nf: Int, val use: Int, val at: Long, val del: Boolean,
)

object SharedList {
    const val DOC = "aiaccounts"
    private const val IMG_MAX = 60000

    private fun bits(o: JSONObject?, keys: List<String>): Int {
        if (o == null) return 7
        var n = 0; keys.forEachIndexed { i, k -> if (o.optBoolean(k, true)) n = n or (1 shl i) }; return n
    }
    private fun obj(n: Int, keys: List<String>) = JSONObject().also { o -> keys.forEachIndexed { i, k -> o.put(k, n and (1 shl i) != 0) } }
    private val NF = listOf("done", "needs", "msgs")
    private val USE = listOf("chats", "needs", "updates")

    fun fromJson(o: JSONObject): Shared {
        val img = o.optString("img").let { if (it.startsWith("data:image/") && it.length <= IMG_MAX) it else "" }
        val col = o.optString("color").let { if (Regex("^#[0-9A-Fa-f]{6}$").matches(it)) it.uppercase() else "" }
        return Shared(o.optString("id"), o.optString("ai"), o.optString("aiName"), o.optString("url"), o.optString("name"),
            o.optString("desc").take(300), o.optString("emoji"), img, col, bits(o.optJSONObject("nf"), NF), bits(o.optJSONObject("use"), USE),
            o.optLong("at", 0L), o.optBoolean("del", false))
    }
    fun toJson(s: Shared): JSONObject = JSONObject().put("id", s.id).put("ai", s.ai).put("aiName", s.aiName).put("url", s.url)
        .put("name", s.name).put("desc", s.desc).put("emoji", s.emoji).put("img", s.img).put("color", s.color)
        .put("nf", obj(s.nf, NF)).put("use", obj(s.use, USE)).put("at", s.at).put("del", s.del)

    fun parse(json: String?): List<Shared> = try {
        val t = (json ?: "").trim()
        val arr = if (t.startsWith("[")) JSONArray(t) else JSONObject(t.ifEmpty { "{}" }).optJSONArray("accounts") ?: JSONArray()
        (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }.filter { it.id.isNotBlank() }
    } catch (e: Exception) { emptyList() }
    fun encode(list: List<Shared>): String = JSONObject().put("v", 1).put("accounts", JSONArray().also { a -> list.forEach { a.put(toJson(it)) } }).toString()

    /** Same id: the newer change wins; a tie keeps `mine`. */
    fun merge(mine: List<Shared>, theirs: List<Shared>): List<Shared> {
        val out = LinkedHashMap<String, Shared>()
        mine.forEach { out[it.id] = it }
        theirs.forEach { t -> val m = out[t.id]; if (m == null || t.at > m.at) out[t.id] = t }
        return out.values.toList()
    }
    /** Equal when every id has the same change time and removed flag (small shape differences between apps never cause a write loop). */
    fun same(a: List<Shared>, b: List<Shared>) = a.map { Triple(it.id, it.at, it.del) }.toSet() == b.map { Triple(it.id, it.at, it.del) }.toSet()

    fun fromAccount(a: Account, img: String): Shared = Shared(a.id, if (a.aiId.startsWith("custom-")) "custom" else a.aiId, a.aiName, a.url, a.nickname, a.note, a.emoji, img,
        if (a.color == 0L) "" else "#%06X".format(a.color and 0xFFFFFF), a.nf, a.use, if (a.at == 0L) 1L else a.at, false)

    /** Turns a shared entry into this phone's account. `catalogUrl` fills the link for AIs the computer saved without one. */
    fun toAccount(s: Shared, old: Account?, catalogUrl: (String) -> String?): Account {
        val aiId = if (s.ai == "custom") "custom-" + s.id else s.ai
        val url = s.url.ifBlank { catalogUrl(s.ai) ?: old?.url ?: "" }
        val color = if (s.color.isBlank()) 0L else s.color.removePrefix("#").toLong(16)
        return Account(s.id, aiId, s.aiName.ifBlank { old?.aiName ?: s.ai }, url, if (s.name == "Main") "" else s.name, s.emoji, s.desc,
            color, s.nf, old?.setup ?: false, s.at, s.use)
    }

    /** Old Android list (settings/android) as shared entries, used once so nothing saved before is lost. */
    fun fromLegacy(json: String?): List<Shared> = Store.decode(json ?: "[]").map { fromAccount(it.copy(at = 1L), "") }
}
