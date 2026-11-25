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
    private val tail = HashMap<String, String>()
    const val PROBE = "(function(){var b=!!document.querySelector('button[aria-label*=\"Stop\" i],[data-testid*=\"stop\" i],[aria-label*=\"Stop response\" i]');var t=((document.body&&document.body.innerText)||'').trim();return JSON.stringify({b:b,t:t.slice(-300)})})()"

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
            .setContentTitle(a.title + ": " + title).setContentText(body).setAutoCancel(true).setContentIntent(pi).build()
        nm.notify((a.id + title).hashCode(), n)
    }

    /** Called every few seconds on the main thread for one open page. */
    fun check(ctx: Context, a: Account, web: WebView, viewing: Boolean) {
        web.evaluateJavascript(PROBE) { raw ->
            try {
                val s = JSONTokener(raw).nextValue() as? String ?: return@evaluateJavascript
                val j = JSONObject(s); val b = j.optBoolean("b"); val t = j.optString("t")
                val pb = busy[a.id]; val pt = tail[a.id]
                busy[a.id] = b; tail[a.id] = t
                if (pb == null || pt == null || viewing) return@evaluateJavascript
                val asks = listOf("do you want me to", "would you like me to", "which one", "please confirm", "waiting for your", "need your approval").any { t.lowercase().contains(it) }
                when {
                    pb && !b && a.nf and NF_DONE != 0 -> post(ctx, a, "Finished", a.title + " is done.")
                    !b && t != pt && t.isNotEmpty() && asks && a.nf and NF_NEEDS != 0 -> post(ctx, a, "Needs you", a.title + " is waiting for your answer.")
                    !b && t != pt && t.isNotEmpty() && a.nf and NF_MSGS != 0 -> post(ctx, a, "New message", t.takeLast(90).replace("\n", " "))
                }
            } catch (_: Exception) {}
        }
    }
}
