package ir.dehgmahdi.planner

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object Backup {
    private const val KEEP = 4

    fun schedule(c: Context, on: Boolean) {
        val wm = WorkManager.getInstance(c)
        if (on) {
            wm.enqueueUniquePeriodicWork(
                "auto-backup", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<BackupWorker>(7, TimeUnit.DAYS).build()
            )
        } else {
            wm.cancelUniqueWork("auto-backup")
        }
    }

    // یک فایل برای هر هفته؛ بنابراین فایل تکراری ساخته نمی‌شود.
    private fun fileName() =
        "planner-auto-" + SimpleDateFormat("yyyy-'W'ww", Locale.US).format(Date()) + ".json"

    fun writeAuto(c: Context, state: String) {
        JSONObject(state) // اعتبارسنجی: اگر خراب باشد استثنا می‌دهد و چیزی نوشته نمی‌شود
        val p = Store.p(c)
        val tree = p.getString("dir", null)
        val name = fileName()
        if (tree != null) {
            val dir = DocumentFile.fromTreeUri(c, Uri.parse(tree))
                ?: throw IllegalStateException("پوشه‌ی انتخابی در دسترس نیست")
            if (!dir.canWrite()) throw IllegalStateException("اجازه‌ی نوشتن در پوشه‌ی انتخابی نیست")
            val f = dir.findFile(name) ?: dir.createFile("application/json", name)
                ?: throw IllegalStateException("ساخت فایل پشتیبان ممکن نشد")
            val out = c.contentResolver.openOutputStream(f.uri, "wt")
                ?: throw IllegalStateException("نوشتن در فایل ممکن نشد")
            out.use { it.write(state.toByteArray(Charsets.UTF_8)) }
            dir.listFiles().filter { it.name?.startsWith("planner-auto-") == true }
                .sortedByDescending { it.name }.drop(KEEP).forEach { it.delete() }
        } else {
            val dir = File(c.filesDir, "backups")
            dir.mkdirs()
            File(dir, name).writeText(state, Charsets.UTF_8)
            dir.listFiles()?.filter { it.name.startsWith("planner-auto-") }
                ?.sortedByDescending { it.name }?.drop(KEEP)?.forEach { it.delete() }
        }
    }

    fun ok(c: Context) {
        Store.p(c).edit().putLong("bkLast", System.currentTimeMillis()).putBoolean("bkOk", true).apply()
    }

    fun fail(c: Context, msg: String) {
        Store.p(c).edit().putLong("bkLast", System.currentTimeMillis()).putBoolean("bkOk", false).apply()
        Alarms.show(c, 900001, "پشتیبان‌گیری خودکار ناموفق بود", msg)
    }
}

class BackupWorker(c: Context, p: WorkerParameters) : Worker(c, p) {
    override fun doWork(): Result {
        val ctx = applicationContext
        val st = Store.p(ctx).getString("state", null) ?: return Result.success()
        try {
            Backup.writeAuto(ctx, st)
            Backup.ok(ctx)
        } catch (e: Exception) {
            Backup.fail(ctx, e.message ?: "خطای نامشخص")
        }
        return Result.success()
    }
}
