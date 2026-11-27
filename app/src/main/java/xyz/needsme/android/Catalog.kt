package xyz.needsme.android

import org.json.JSONArray

/** One AI website from assets/catalog.json (same list as the Mac app). */
data class AI(val id: String, val name: String, val url: String, val group: String, val convo: String = "")

object Catalog {
    fun parse(json: String): List<AI> {
        val a = JSONArray(json)
        return (0 until a.length()).map { a.getJSONObject(it) }
            .filter { it.optString("how") == "web" }
            .map { AI(it.getString("id"), it.getString("name"), it.getString("url"), it.optString("group", "Chat"), it.optString("convo")) }
    }
    fun filter(all: List<AI>, query: String, group: String): List<AI> =
        all.filter { (group == "All" || it.group == group) && it.name.contains(query.trim(), ignoreCase = true) }
}
