package ir.dehgmahdi.planner

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import org.json.JSONArray

class PlannerWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { update(context, manager, it) }
    }

    companion object {
        fun update(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.planner_widget)
            val raw = context.getSharedPreferences("planner_widget", Context.MODE_PRIVATE).getString("tasks", "[]") ?: "[]"
            val tasks = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
            val count = minOf(tasks.length(), 5)
            val rowIds = intArrayOf(R.id.widget_task_0, R.id.widget_task_1, R.id.widget_task_2, R.id.widget_task_3, R.id.widget_task_4)
            for (i in 0 until 5) {
                val item = if (i < count) tasks.optJSONObject(i) else null
                if (item == null) {
                    views.setTextViewText(rowIds[i], if (i == 0 && count == 0) "امروز کاری ثبت نشده" else "")
                    views.setOnClickPendingIntent(rowIds[i], null)
                    continue
                }
                val id = item.optString("id")
                val title = item.optString("title", "کار بدون عنوان")
                val time = item.optString("time")
                val label = if (time.isBlank()) "□  $title" else "□  $title  ·  $time"
                views.setTextViewText(rowIds[i], label)
                val intent = Intent(context, PlannerWidgetActionReceiver::class.java).apply {
                    action = PlannerWidgetActionReceiver.ACTION_DONE
                    putExtra("task_id", id)
                }
                val pi = PendingIntent.getBroadcast(context, id.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                views.setOnClickPendingIntent(rowIds[i], pi)
            }
            val addIntent = Intent(context, PlannerWidgetActionReceiver::class.java).apply { action = PlannerWidgetActionReceiver.ACTION_ADD }
            val addPi = PendingIntent.getBroadcast(context, 90001, addIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.widget_add, addPi)
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            if (launch != null) {
                val openPi = PendingIntent.getActivity(context, 90002, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                views.setOnClickPendingIntent(R.id.widget_title, openPi)
            }
            manager.updateAppWidget(widgetId, views)
        }

        private fun taskButtonId(index: Int): Int = when(index) {
            0 -> R.id.widget_task_0
            1 -> R.id.widget_task_1
            2 -> R.id.widget_task_2
            3 -> R.id.widget_task_3
            else -> R.id.widget_task_4
        }
    }
}

class PlannerWidgetActionReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("planner_widget", Context.MODE_PRIVATE)
        when (intent.action) {
            ACTION_DONE -> {
                val id = intent.getStringExtra("task_id") ?: return
                val old = try { JSONArray(prefs.getString("pending", "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
                old.put(id)
                prefs.edit().putString("pending", old.toString()).apply()
            }
            ACTION_ADD -> prefs.edit().putBoolean("add_pending", true).apply()
        }
        val manager = AppWidgetManager.getInstance(context)
        manager.getAppWidgetIds(ComponentName(context, PlannerWidgetProvider::class.java)).forEach {
            PlannerWidgetProvider.update(context, manager, it)
        }
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            context.startActivity(launch)
        }
    }

    companion object {
        const val ACTION_DONE = "ir.dehgmahdi.planner.WIDGET_DONE"
        const val ACTION_ADD = "ir.dehgmahdi.planner.WIDGET_ADD"
    }
}
