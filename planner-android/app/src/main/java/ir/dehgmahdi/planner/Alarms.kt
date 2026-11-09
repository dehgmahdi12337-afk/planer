package ir.dehgmahdi.planner

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONArray

object Alarms {
    const val CH = "reminders"

    fun channel(c: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CH, "یادآوری کارها", NotificationManager.IMPORTANCE_HIGH)
            ch.enableVibration(true)
            c.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun pi(c: Context, id: Int, t: String, b: String): PendingIntent {
        val i = Intent(c, AlarmReceiver::class.java).setAction("ALARM")
            .putExtra("id", id).putExtra("t", t).putExtra("b", b)
        return PendingIntent.getBroadcast(
            c, id, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun cancel(c: Context, id: Int) {
        val i = Intent(c, AlarmReceiver::class.java).setAction("ALARM")
        val p = PendingIntent.getBroadcast(
            c, id, i, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (p != null) {
            c.getSystemService(AlarmManager::class.java).cancel(p)
            p.cancel()
        }
    }

    fun schedule(c: Context, id: Int, at: Long, t: String, b: String) {
        val am = c.getSystemService(AlarmManager::class.java)
        val p = pi(c, id, t, b)
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
        }
    }

    /** json = [{id, at, title, body}] ؛ همه‌ی یادآورهای قبلی لغو و فهرست جدید جایگزین می‌شود. */
    fun apply(c: Context, json: String) {
        val p = Store.p(c)
        val old = JSONArray(p.getString("alarmIds", "[]"))
        for (i in 0 until old.length()) cancel(c, old.getInt(i))
        val arr = JSONArray(json)
        val ids = JSONArray()
        val now = System.currentTimeMillis()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val at = o.getLong("at")
            if (at <= now) continue
            val id = o.getInt("id")
            ids.put(id)
            schedule(c, id, at, o.getString("title"), o.getString("body"))
        }
        p.edit().putString("alarmIds", ids.toString()).putString("alarms", json).apply()
    }

    @SuppressLint("MissingPermission")
    fun show(c: Context, id: Int, t: String, b: String) {
        channel(c)
        val open = PendingIntent.getActivity(
            c, id,
            Intent(c, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val snooze = PendingIntent.getBroadcast(
            c, id xor 0x3FFFFFFF,
            Intent(c, AlarmReceiver::class.java).setAction("SNOOZE")
                .putExtra("id", id).putExtra("t", t).putExtra("b", b),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(c, CH)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(t)
            .setContentText(b)
            .setStyle(NotificationCompat.BigTextStyle().bigText(b))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .addAction(0, "۱۰ دقیقه بعد", snooze)
            .build()
        try {
            NotificationManagerCompat.from(c).notify(id, n)
        } catch (e: SecurityException) {
        }
    }
}

class AlarmReceiver : BroadcastReceiver() {
    @SuppressLint("MissingPermission")
    override fun onReceive(c: Context, i: Intent) {
        val id = i.getIntExtra("id", 0)
        val t = i.getStringExtra("t") ?: "پلنر"
        val b = i.getStringExtra("b") ?: ""
        if (i.action == "SNOOZE") {
            NotificationManagerCompat.from(c).cancel(id)
            Alarms.schedule(c, id, System.currentTimeMillis() + 10 * 60_000L, t, b)
        } else {
            Alarms.show(c, id, t, b)
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val p = Store.p(c)
        try {
            Alarms.apply(c, p.getString("alarms", "[]") ?: "[]")
        } catch (e: Exception) {
        }
        Backup.schedule(c, p.getBoolean("auto", true))
        PlannerWidget.refresh(c)
    }
}
