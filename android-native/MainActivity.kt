package ir.dehgmahdi.planner

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import org.json.JSONArray
import java.util.TimeZone

class MainActivity : FlutterActivity() {
    private val channelName = "planner/native"
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channelName).setMethodCallHandler { call, result ->
            when (call.method) {
                "getTimeZone" -> result.success(TimeZone.getDefault().id)
                "setTasks" -> {
                    val raw = call.arguments as? String ?: "[]"
                    getSharedPreferences("planner_widget", Context.MODE_PRIVATE).edit().putString("tasks", raw).apply()
                    val manager = AppWidgetManager.getInstance(this)
                    val ids = manager.getAppWidgetIds(ComponentName(this, PlannerWidgetProvider::class.java))
                    ids.forEach { PlannerWidgetProvider.update(this, manager, it) }
                    result.success(null)
                }
                "takePending" -> {
                    val prefs = getSharedPreferences("planner_widget", Context.MODE_PRIVATE)
                    val ids = prefs.getString("pending", "[]") ?: "[]"
                    prefs.edit().putString("pending", "[]").apply()
                    result.success(ids)
                }
                "takeAdd" -> {
                    val prefs = getSharedPreferences("planner_widget", Context.MODE_PRIVATE)
                    val value = prefs.getBoolean("add_pending", false)
                    prefs.edit().putBoolean("add_pending", false).apply()
                    result.success(value)
                }
                "canScheduleExactAlarms" -> {
                    result.success(Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                        getSystemService(android.app.AlarmManager::class.java).canScheduleExactAlarms())
                }
                "openExactAlarmSettings" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
                    }
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }
    }
}
