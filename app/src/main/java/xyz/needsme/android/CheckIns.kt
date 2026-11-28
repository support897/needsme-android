package xyz.needsme.android

import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import java.util.Calendar
import java.util.concurrent.TimeUnit

/** Three check-in alerts a day (9, 13, 18). Free, local, no server. Tapping one opens the app, which syncs. */
object CheckIns {
    private val hours = listOf(9, 13, 18)
    private val text = listOf("Good morning. Open NeedsMe to see your projects and what needs you.", "Midday check-in. Open NeedsMe to see what needs you.", "Evening check-in. Open NeedsMe to see today's updates.")
    fun on(c: Context) = c.getSharedPreferences("nm_checkins", Context.MODE_PRIVATE).getBoolean("on", true)
    fun set(c: Context, v: Boolean) { c.getSharedPreferences("nm_checkins", Context.MODE_PRIVATE).edit().putBoolean("on", v).apply(); schedule(c) }
    fun delayTo(hour: Int, now: Calendar = Calendar.getInstance()): Long {
        val t = now.clone() as Calendar; t.set(Calendar.HOUR_OF_DAY, hour); t.set(Calendar.MINUTE, 0); t.set(Calendar.SECOND, 0); t.set(Calendar.MILLISECOND, 0)
        if (t.timeInMillis <= now.timeInMillis) t.add(Calendar.DAY_OF_YEAR, 1)
        return t.timeInMillis - now.timeInMillis
    }
    fun schedule(c: Context) {
        val wm = WorkManager.getInstance(c)
        hours.forEachIndexed { i, h ->
            val name = "nm_checkin_$h"
            if (!on(c)) { wm.cancelUniqueWork(name); return@forEachIndexed }
            val req = PeriodicWorkRequestBuilder<CheckInWorker>(24, TimeUnit.HOURS).setInitialDelay(delayTo(h), TimeUnit.MILLISECONDS)
                .setInputData(workDataOf("i" to i)).build()
            wm.enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }
    fun post(c: Context, i: Int) {
        if (Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(c, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("nm_checkin", "Daily check-ins", NotificationManager.IMPORTANCE_DEFAULT))
        val pi = PendingIntent.getActivity(c, 900 + i, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        nm.notify(900 + i, NotificationCompat.Builder(c, "nm_checkin").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("NeedsMe").setContentText(text[i]).setAutoCancel(true).setContentIntent(pi).build())
    }
}

class CheckInWorker(ctx: Context, p: WorkerParameters) : Worker(ctx, p) {
    override fun doWork(): Result { CheckIns.post(applicationContext, inputData.getInt("i", 0)); return Result.success() }
}

/** Item 1: remember a crash, send it to the bridge on the next launch so it can be read and fixed. */
object CrashLog {
    private fun p(c: Context) = c.getSharedPreferences("nm_crash", Context.MODE_PRIVATE)
    fun install(c: Context) {
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { p(c).edit().putString("last", (e.toString() + "\n" + e.stackTrace.take(25).joinToString("\n")).take(3500)).commit() } catch (_: Throwable) {}
            old?.uncaughtException(t, e)
        }
    }
    fun sendPending(c: Context) {
        val txt = p(c).getString("last", null) ?: return
        try {
            val h = java.net.URL(Bridge.BASE + "/report").openConnection() as java.net.HttpURLConnection
            h.requestMethod = "POST"; h.doOutput = true; h.connectTimeout = 15000; h.readTimeout = 15000
            h.setRequestProperty("Content-Type", "application/json")
            val body = org.json.JSONObject().put("kind", "bug").put("text", "Android crash\n$txt").put("info", "Android ${Build.VERSION.SDK_INT} ${Build.MODEL}").toString()
            h.outputStream.use { it.write(body.toByteArray()) }
            if (h.responseCode in 200..299) p(c).edit().remove("last").apply()
        } catch (_: Exception) {}
    }
}
