package ir.dehgmahdi.planner

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StrikethroughSpan
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import org.json.JSONArray
import org.json.JSONObject

class PlannerWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) {
        for (id in ids) m.updateAppWidget(id, build(c))
        m.notifyAppWidgetViewDataChanged(ids, R.id.list)
    }

    companion object {
        fun build(c: Context): RemoteViews {
            val rv = RemoteViews(c.packageName, R.layout.widget)
            val dark = Store.p(c).getBoolean("widgetDark", false)
            rv.setInt(R.id.widget_root, "setBackgroundColor", Color.parseColor(if (dark) "#252A29" else "#111111"))
            rv.setTextColor(R.id.head, Color.parseColor("#BDEBE3"))
            rv.setRemoteAdapter(R.id.list, Intent(c, WidgetService::class.java))
            rv.setEmptyView(R.id.list, R.id.empty)
            val imm = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val add = Intent(c, MainActivity::class.java).setAction("ADD")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            rv.setOnClickPendingIntent(R.id.add, PendingIntent.getActivity(c, 0, add, imm))
            val open = Intent(c, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            rv.setOnClickPendingIntent(R.id.head, PendingIntent.getActivity(c, 2, open, imm))
            val mut = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            rv.setPendingIntentTemplate(
                R.id.list,
                PendingIntent.getActivity(c, 1, Intent(c, WidgetActionActivity::class.java), mut)
            )
            return rv
        }

        fun refresh(c: Context) {
            val m = AppWidgetManager.getInstance(c)
            val ids = m.getAppWidgetIds(ComponentName(c, PlannerWidget::class.java))
            if (ids.isEmpty()) return
            for (id in ids) m.updateAppWidget(id, build(c))
            m.notifyAppWidgetViewDataChanged(ids, R.id.list)
        }
    }
}

class WidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Factory(applicationContext)

    class Factory(private val c: Context) : RemoteViewsFactory {
        private var items = JSONArray()

        private fun load() {
            items = try {
                JSONArray(Store.p(c).getString("wd", "[]"))
            } catch (e: Exception) {
                JSONArray()
            }
        }

        override fun onCreate() { load() }
        override fun onDataSetChanged() { load() }
        override fun onDestroy() {}
        override fun getCount(): Int = items.length()

        override fun getViewAt(i: Int): RemoteViews {
            val o = items.getJSONObject(i)
            val done = o.optBoolean("done")
            val rv = RemoteViews(c.packageName, R.layout.widget_item)
            val s = SpannableString(o.getString("title"))
            if (done) s.setSpan(StrikethroughSpan(), 0, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            rv.setTextViewText(R.id.title, s)
            val dark = Store.p(c).getBoolean("widgetDark", false)
            rv.setTextColor(R.id.title, if (done) Color.GRAY else if (dark) Color.WHITE else Color.parseColor("#F4F5F4"))
            rv.setTextViewText(R.id.time, o.optString("time"))
            rv.setTextColor(R.id.time, if (dark) Color.parseColor("#BDEBE3") else Color.parseColor("#AAB4C8"))
            val priColor = when (o.optInt("pri", 0)) { 2 -> "#E76B68"; 1 -> "#F5C978"; else -> "#39B98A" }
            rv.setInt(R.id.priority, "setBackgroundColor", Color.parseColor(priColor))
            rv.setImageViewResource(R.id.check, if (done) R.drawable.ic_check_on else R.drawable.ic_check_off)
            rv.setOnClickFillInIntent(
                R.id.check,
                Intent().putExtra("act", "done").putExtra("id", o.getString("id")).putExtra("key", o.getString("key"))
            )
            rv.setOnClickFillInIntent(
                R.id.title,
                Intent().putExtra("act", "open").putExtra("id", o.getString("id"))
            )
            return rv
        }

        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount(): Int = 1
        override fun getItemId(i: Int): Long = i.toLong()
        override fun hasStableIds(): Boolean = false
    }
}

/** پل بدون رابط: تیک زدن یا باز کردن کار از ویجت. */
class WidgetActionActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val act = intent.getStringExtra("act")
        val id = intent.getStringExtra("id") ?: ""
        val key = intent.getStringExtra("key") ?: ""
        val p = Store.p(this)
        try {
            if (act == "done") {
                val wd = JSONArray(p.getString("wd", "[]"))
                var estimate = 0
                for (i in 0 until wd.length()) {
                    val item = wd.getJSONObject(i)
                    if (item.optString("id") == id && item.optString("key") == key) estimate = item.optInt("est", 0)
                }
                if (estimate > 0) {
                    p.edit().putString("widgetTime", JSONObject().put("id", id).put("key", key).toString()).apply()
                    startActivity(Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                    finish()
                    return
                }
                var changed = false
                for (i in 0 until wd.length()) {
                    val o = wd.getJSONObject(i)
                    if (o.getString("id") == id && o.getString("key") == key && !o.optBoolean("done")) {
                        o.put("done", true)
                        changed = true
                    }
                }
                if (changed) {
                    val pend = JSONArray(p.getString("pending", "[]"))
                    pend.put(JSONObject().put("id", id).put("key", key))
                    p.edit().putString("wd", wd.toString()).putString("pending", pend.toString()).apply()
                    PlannerWidget.refresh(this)
                }
            } else if (act == "open") {
                p.edit().putString("open", id).apply()
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
            }
        } catch (e: Exception) {
        }
        finish()
    }
}
