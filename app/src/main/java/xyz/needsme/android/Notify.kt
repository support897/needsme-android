package xyz.needsme.android

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.webkit.WebView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.json.JSONTokener

/** Alert switches for one account. Bit 1 = finished, 2 = needs me, 4 = new message. */
const val NF_DONE = 1
const val NF_NEEDS = 2
const val NF_MSGS = 4
const val NF_ALL = 7

/** Looks at each open AI page. Posts a phone alert that opens that account when a reply finishes or a new message lands. */
object Notify {
    private const val CHANNEL = "needsme_alerts"
    private val busy = HashMap<String, Boolean>()
    private val last = HashMap<String, String>()
    private val sent = HashMap<String, String>()
    /** Reads the open AI page: is it still typing (b), the AI's own last reply (l), and its chat list (c). */
    const val PROBE = "(function(){var b=!!document.querySelector('button[aria-label*=\\\"Stop\\\" i],[data-testid*=\\\"stop\\\" i],[aria-label*=\\\"Stop response\\\" i]');" +
        "var Q=['[data-message-author-role=\\\"assistant\\\"]','.font-claude-response','[data-is-streaming]','.font-claude-message','message-content','model-response','[data-testid=\\\"bot-message\\\"]','[data-content=\\\"ai-message\\\"]','.prose','.markdown'],l='';" +
        "for(var i=0;i<Q.length;i++){var e=[].slice.call(document.querySelectorAll(Q[i])).filter(function(x){return (x.innerText||'').trim()});if(e.length){l=(e[e.length-1].innerText||'').trim();break}}" +
        "var c=[],seen={},sel=window.__nmConvo||'nav a[href], aside a[href]';try{[].slice.call(document.querySelectorAll(sel)).forEach(function(a){var t=(a.innerText||'').trim().split('\\n')[0];if(t&&t.length>2&&!seen[a.href]&&c.length<40){seen[a.href]=1;c.push({t:t.slice(0,80),h:a.href})}})}catch(x){}" +
        "return JSON.stringify({b:b,l:l.slice(0,1500),c:c})})()"
    /** Latest chat list per account (item 4: chats pulled from each AI). */
    val chats = HashMap<String, List<Pair<String, String>>>()

    fun ask(act: Activity) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(act, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            act.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
    }

    fun post(ctx: Context, a: Account, title: String, body: String) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "AI alerts", NotificationManager.IMPORTANCE_HIGH))
        val i = Intent(ctx, MainActivity::class.java).putExtra("acct", a.id).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(ctx, a.id.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title).setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true).setContentIntent(pi).build()
        nm.notify((a.id + body).hashCode(), n)
    }

    /** Title of every alert: which AI and which account, like "ChatGPT · Work". */
    fun titleOf(a: Account) = if (a.nickname.isBlank() || a.nickname == a.aiName) a.aiName else a.aiName + " \u00b7 " + a.nickname
    private val ASKS = listOf("do you want me to", "would you like me to", "which one", "please confirm", "waiting for your", "need your approval", "?")

    /** Decides one alert from two readings of the page. Returns the alert body (the AI's exact words) or null. Pure, so it is unit tested. */
    fun decide(prevBusy: Boolean?, prevLast: String?, busy: Boolean, lastReply: String, alreadySent: String?, viewing: Boolean, nf: Int, use: Int): String? {
        if (prevBusy == null || prevLast == null || viewing || busy) return null
        if (lastReply.isBlank() || lastReply == prevLast || lastReply == alreadySent) return null
        val asks = ASKS.any { lastReply.trimEnd().lowercase().let { t -> if (it == "?") t.endsWith("?") else t.contains(it) } }
        val ok = when {
            asks -> nf and NF_NEEDS != 0 && use and 2 != 0
            prevBusy -> nf and NF_DONE != 0
            else -> nf and NF_MSGS != 0
        }
        return if (ok) lastReply else null
    }

    /** Called every few seconds on the main thread for one open page. */
    fun check(ctx: Context, a: Account, web: WebView, viewing: Boolean, convoSel: String?) {
        if (convoSel != null) web.evaluateJavascript("window.__nmConvo=" + JSONObject.quote(convoSel), null)
        web.evaluateJavascript(PROBE) { raw ->
            try {
                val s = JSONTokener(raw).nextValue() as? String ?: return@evaluateJavascript
                val j = JSONObject(s); val b = j.optBoolean("b"); val l = j.optString("l")
                if (a.use and 1 != 0) j.optJSONArray("c")?.let { c -> chats[a.id] = (0 until c.length()).map { c.getJSONObject(it).let { o -> o.optString("t") to o.optString("h") } } }
                val body = decide(busy[a.id], last[a.id], b, l, sent[a.id], viewing, a.nf, a.use)
                busy[a.id] = b; if (!b) last[a.id] = l
                if (body != null) { sent[a.id] = body; post(ctx, a, titleOf(a), body) }
            } catch (_: Exception) {}
        }
    }
}
