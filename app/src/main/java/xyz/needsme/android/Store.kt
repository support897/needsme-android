package xyz.needsme.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A saved login for one AI: its own name, emoji, note and its own private browser storage. */
data class Account(
    val id: String = UUID.randomUUID().toString().take(8),
    val aiId: String,
    val aiName: String,
    val url: String,
    val nickname: String,
    val emoji: String = "",
    val note: String = "",
    val color: Long = 0L,
    val nf: Int = 7,
    val setup: Boolean = false,
) {
    val profile get() = "acct_$id"
    val title get() = nickname.ifBlank { aiName }
    val initial get() = emoji.ifBlank { title.take(1).uppercase() }
}

/** Accounts and open tabs, kept on this phone. The same list is synced to the Needs Me account (Sync.kt). */
class Store(ctx: Context) {
    private val sp = ctx.getSharedPreferences("needsme", Context.MODE_PRIVATE)
    var accounts: List<Account> = decode(sp.getString("accounts", "[]") ?: "[]")
        private set
    var openTabs: List<String> = sp.getString("tabs", "")!!.split(",").filter { it.isNotBlank() }
        private set

    fun save(list: List<Account>) { accounts = list; sp.edit().putString("accounts", encode(list)).apply() }
    fun setTabs(ids: List<String>) { openTabs = ids; sp.edit().putString("tabs", ids.joinToString(",")).apply() }
    fun account(id: String) = accounts.firstOrNull { it.id == id }
    fun clear() { accounts = emptyList(); openTabs = emptyList(); sp.edit().clear().apply() }
    fun session(key: String) = sp.getString(key, null)
    fun putSession(key: String, v: String?) { sp.edit().apply { if (v == null) remove(key) else putString(key, v) }.apply() }

    companion object {
        fun encode(list: List<Account>): String = JSONArray().also { arr ->
            list.forEach { arr.put(JSONObject().put("id", it.id).put("aiId", it.aiId).put("aiName", it.aiName).put("url", it.url)
                .put("nickname", it.nickname).put("emoji", it.emoji).put("note", it.note).put("color", it.color).put("nf", it.nf).put("setup", it.setup)) }
        }.toString()
        fun decode(json: String): List<Account> = try {
            val a = JSONArray(json)
            (0 until a.length()).map { a.getJSONObject(it) }.map {
                Account(it.getString("id"), it.getString("aiId"), it.getString("aiName"), it.getString("url"),
                    it.optString("nickname"), it.optString("emoji"), it.optString("note"), it.optLong("color", 0L), it.optInt("nf", 7), it.optBoolean("setup", false))
            }
        } catch (e: Exception) { emptyList() }
    }
}
