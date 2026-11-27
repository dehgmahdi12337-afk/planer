package ir.dehgmahdi.planner

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

object Backup {
    // همیشه یک فایل با همین نام؛ هر بار روی همان بازنویسی می‌شود.
    const val FILE_NAME = "planner-auto-backup.json"
    private const val DAY = 24L * 60 * 60 * 1000

    // پشتیبان‌گیری روزانه. UPDATE یعنی زمان‌بندی هفتگی قدیمی جایگزین می‌شود.
    fun schedule(c: Context, on: Boolean) {
        val wm = WorkManager.getInstance(c)
        if (on) {
            wm.enqueueUniquePeriodicWork(
                "auto-backup", ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.DAYS).build()
            )
        } else {
            wm.cancelUniqueWork("auto-backup")
        }
    }

    @Synchronized
    fun writeAuto(c: Context, state: String) {
        JSONObject(state) // اعتبارسنجی: اگر خراب باشد استثنا می‌دهد و چیزی نوشته نمی‌شود
        val tree = Store.p(c).getString("dir", null)
        if (tree != null) {
            val dir = DocumentFile.fromTreeUri(c, Uri.parse(tree))
                ?: throw IllegalStateException("پوشه‌ی انتخابی در دسترس نیست")
            if (!dir.canWrite()) throw IllegalStateException("اجازه‌ی نوشتن در پوشه‌ی انتخابی نیست")
            val f = dir.findFile(FILE_NAME) ?: dir.createFile("application/json", FILE_NAME)
                ?: throw IllegalStateException("ساخت فایل پشتیبان ممکن نشد")
            val out = c.contentResolver.openOutputStream(f.uri, "wt")
                ?: throw IllegalStateException("نوشتن در فایل ممکن نشد")
            out.use { it.write(state.toByteArray(Charsets.UTF_8)) }
        } else {
            val dir = File(c.filesDir, "backups")
            dir.mkdirs()
            val tmp = File(dir, "$FILE_NAME.tmp")
            tmp.writeText(state, Charsets.UTF_8)
            val dst = File(dir, FILE_NAME)
            if (!tmp.renameTo(dst)) {
                dst.writeText(state, Charsets.UTF_8)
                tmp.delete()
            }
        }
    }

    fun ok(c: Context) {
        val now = System.currentTimeMillis()
        Store.p(c).edit().putLong("bkLast", now).putLong("bkAuto", now).putBoolean("bkOk", true).apply()
    }

    fun fail(c: Context, msg: String) {
        Store.p(c).edit().putLong("bkLast", System.currentTimeMillis()).putBoolean("bkOk", false).apply()
        Alarms.show(c, 900001, "پشتیبان‌گیری خودکار ناموفق بود", msg)
    }

    /** یک بار پشتیبان می‌گیرد؛ نتیجه (موفق/ناموفق) ثبت می‌شود. */
    fun runNow(c: Context): Boolean {
        val st = Store.p(c).getString("state", null) ?: return false
        return try {
            writeAuto(c, st)
            ok(c)
            true
        } catch (e: Exception) {
            fail(c, e.message ?: "خطای نامشخص")
            false
        }
    }

    /**
     * اگر بیش از ۲۴ ساعت از آخرین پشتیبان خودکار گذشته باشد، همین حالا (در پس‌زمینه) پشتیبان می‌گیرد.
     * این کار جلوی تأخیر WorkManager به‌خاطر محدودیت باتری را می‌گیرد؛ با هر بار باز کردن برنامه بررسی می‌شود.
     * force=true برای وقتی است که کاربر پوشه را عوض یا پشتیبان خودکار را روشن می‌کند.
     */
    fun runIfDue(c: Context, force: Boolean = false, onDone: (() -> Unit)? = null) {
        val app = c.applicationContext
        val p = Store.p(app)
        if (!p.getBoolean("auto", true)) return
        val due = force || System.currentTimeMillis() - p.getLong("bkAuto", 0) >= DAY
        if (!due) return
        Thread {
            runNow(app)
            onDone?.invoke()
        }.start()
    }
}

class BackupWorker(c: Context, p: WorkerParameters) : Worker(c, p) {
    override fun doWork(): Result {
        Backup.runNow(applicationContext)
        return Result.success()
    }
}
