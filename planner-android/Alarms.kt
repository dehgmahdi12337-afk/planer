package ir.dehgmahdi.planner

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONArray
import java.util.Calendar

object Alarms {
    const val CH = "reminders"

    fun channel(c: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CH, "یادآوری کارها", NotificationManager.IMPORTANCE_HIGH)
            ch.enableVibration(true)
            c.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun pi(c: Context, id: Int, t: String, b: String, color: String): PendingIntent {
        val i = Intent(c, AlarmReceiver::class.java).setAction("ALARM")
            .putExtra("id", id).putExtra("t", t).putExtra("b", b).putExtra("color", color)
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

    fun schedule(c: Context, id: Int, at: Long, t: String, b: String, color: String = "#39B98A") {
        val am = c.getSystemService(AlarmManager::class.java)
        val p = pi(c, id, t, b, color)
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

    /** json = [{id, at, title, body, color}] ؛ همه‌ی یادآورهای قبلی لغو و فهرست جدید جایگزین می‌شود. */
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
            schedule(c, id, at, o.getString("title"), o.optString("body", ""), o.optString("color", "#39B98A"))
        }
        p.edit().putString("alarmIds", ids.toString()).putString("alarms", json).apply()
    }

    /** هر نیمه‌شب ویجت را برای روز جدید دوباره می‌سازد. */
    fun armMidnight(c: Context) {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 5)
        cal.set(Calendar.MILLISECOND, 0)
        val p = PendingIntent.getBroadcast(
            c, 777, Intent(c, AlarmReceiver::class.java).setAction("MIDNIGHT"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            c.getSystemService(AlarmManager::class.java)
                .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, p)
        } catch (e: SecurityException) {
        }
    }

    @SuppressLint("MissingPermission")
    fun show(c: Context, id: Int, t: String, b: String, color: String = "#39B98A") {
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
                .putExtra("id", id).putExtra("t", t).putExtra("b", b).putExtra("color", color),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(c, CH)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(t)
            .setContentText(if (b.isBlank()) null else b)
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (b.isBlank()) t else b))
            .setColor(Color.parseColor(color))
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
        if (i.action == "MIDNIGHT") {
            PlannerWidget.refresh(c)
            Alarms.armMidnight(c)
            return
        }
        val id = i.getIntExtra("id", 0)
        val t = i.getStringExtra("t") ?: "پلنر"
        val b = i.getStringExtra("b") ?: ""
        val color = i.getStringExtra("color") ?: "#39B98A"
        if (i.action == "SNOOZE") {
            NotificationManagerCompat.from(c).cancel(id)
            Alarms.schedule(c, id, System.currentTimeMillis() + 10 * 60_000L, t, b, color)
        } else {
            Alarms.show(c, id, t, b, color)
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
        Alarms.armMidnight(c)
        Backup.schedule(c, p.getBoolean("auto", true))
        PlannerWidget.refresh(c)
    }
}
