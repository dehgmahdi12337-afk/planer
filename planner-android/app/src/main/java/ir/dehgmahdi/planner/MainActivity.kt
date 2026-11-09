package ir.dehgmahdi.planner

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.documentfile.provider.DocumentFile
import androidx.webkit.WebViewAssetLoader
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var web: WebView
    private var pageReady = false
    private var pendingExport: String? = null
    private val sp get() = Store.p(this)

    private val reqNotif =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { push() }

    private val createBackup =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            write(uri, sp.getString("state", "{}") ?: "{}", true)
        }
    private val createJson =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            write(uri, pendingExport ?: "", false); pendingExport = null
        }
    private val createCsv =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            write(uri, pendingExport ?: "", false); pendingExport = null
        }
    private val openDoc =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                try {
                    val text = contentResolver.openInputStream(uri)!!
                        .bufferedReader(Charsets.UTF_8).use { it.readText() }
                    js("onNative({k:'restore',text:" + JSONObject.quote(text) + "})")
                } catch (e: Exception) {
                    toast("خواندن فایل ممکن نشد")
                }
            }
        }
    private val openTree =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (e: Exception) {
                }
                sp.edit().putString("dir", uri.toString()).apply()
                push()
            }
        }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        Alarms.channel(this)

        web = WebView(this)
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setSupportZoom(false)
            builtInZoomControls = false
            allowFileAccess = false
            allowContentAccess = false
            textZoom = 100
        }
        web.overScrollMode = View.OVER_SCROLL_NEVER
        web.isLongClickable = false
        web.setOnLongClickListener { true }
        web.setBackgroundColor(Color.parseColor("#F5F7FA"))
        web.addJavascriptInterface(Bridge(), "Android")
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView, request: WebResourceRequest
            ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)

            override fun onPageFinished(view: WebView, url: String) {
                pageReady = true
                push()
                pull()
            }
        }
        setContentView(web)
        ViewCompat.setOnApplyWindowInsetsListener(web) { v, ins ->
            val b = ins.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            WindowInsetsCompat.CONSUMED
        }
        handle(intent)
        web.loadUrl("https://appassets.androidplatform.net/assets/planner.html")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("(window.onBack?onBack():false)") { r ->
                    if (r != "true") finish()
                }
            }
        })
        Backup.schedule(this, sp.getBoolean("auto", true))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
        pull()
    }

    override fun onResume() {
        super.onResume()
        push()
        pull()
    }

    private fun handle(i: Intent?) {
        if (i != null && i.action == "ADD") {
            sp.edit().putBoolean("add", true).apply()
            i.action = null
        }
    }

    private fun js(code: String) {
        if (!::web.isInitialized) return
        web.post { web.evaluateJavascript("try{window.onNative&&$code}catch(e){}", null) }
    }

    private fun toast(msg: String) = js("onNative({k:'toast',msg:" + JSONObject.quote(msg) + "})")

    private fun status(): String {
        val o = JSONObject()
        o.put("k", "status")
        o.put("perm", NotificationManagerCompat.from(this).areNotificationsEnabled())
        val tree = sp.getString("dir", null)
        o.put("dir", if (tree != null) (DocumentFile.fromTreeUri(this, Uri.parse(tree))?.name ?: "") else "")
        o.put("last", sp.getLong("bkLast", 0))
        o.put("ok", sp.getBoolean("bkOk", true))
        o.put("auto", sp.getBoolean("auto", true))
        o.put("ver", try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (e: Exception) { "" })
        return o.toString()
    }

    private fun push() {
        if (pageReady) js("onNative(" + status() + ")")
    }

    private fun pull() {
        if (!pageReady) return
        val o = JSONObject()
        o.put("k", "pull")
        o.put("pending", JSONArray(sp.getString("pending", "[]")))
        o.put("open", sp.getString("open", "") ?: "")
        o.put("add", sp.getBoolean("add", false))
        sp.edit().putString("pending", "[]").putString("open", "").putBoolean("add", false).apply()
        js("onNative(" + o.toString() + ")")
    }

    private fun write(uri: Uri?, text: String, backup: Boolean) {
        if (uri == null) return
        try {
            contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            if (backup) sp.edit().putLong("bkLast", System.currentTimeMillis()).putBoolean("bkOk", true).apply()
            toast("فایل ذخیره شد")
        } catch (e: Exception) {
            toast("ذخیره‌ی فایل ناموفق بود")
        }
        push()
    }

    private fun askNotif() {
        if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            push(); return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            (!sp.getBoolean("askedNotif", false) ||
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))
        ) {
            sp.edit().putBoolean("askedNotif", true).apply()
            reqNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            )
        }
    }

    private fun date() = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    inner class Bridge {
        @JavascriptInterface
        fun sync(json: String) {
            try {
                val o = JSONObject(json)
                sp.edit().putString("state", o.getString("state"))
                    .putString("wd", o.getJSONArray("widget").toString()).apply()
                Alarms.apply(this@MainActivity, o.getJSONArray("alarms").toString())
                PlannerWidget.refresh(this@MainActivity)
            } catch (e: Exception) {
            }
        }

        @JavascriptInterface
        fun perm() { runOnUiThread { askNotif() } }

        @JavascriptInterface
        fun test() {
            if (NotificationManagerCompat.from(this@MainActivity).areNotificationsEnabled()) {
                Alarms.show(this@MainActivity, 424242, "پلنر · اعلان آزمایشی", "اعلان‌ها درست کار می‌کنند.")
            } else {
                toast("اجازه‌ی اعلان داده نشده؛ اول «فعال‌سازی اعلان» را بزن.")
            }
        }

        @JavascriptInterface
        fun backupNow() { runOnUiThread { createBackup.launch("planner-backup-" + date() + ".json") } }

        @JavascriptInterface
        fun restorePick() { runOnUiThread { openDoc.launch(arrayOf("*/*")) } }

        @JavascriptInterface
        fun pickDir() { runOnUiThread { openTree.launch(null) } }

        @JavascriptInterface
        fun autoBackup(on: Boolean) {
            sp.edit().putBoolean("auto", on).apply()
            Backup.schedule(this@MainActivity, on)
            runOnUiThread { push() }
        }

        @JavascriptInterface
        fun exportFile(name: String, mime: String, content: String) {
            runOnUiThread {
                pendingExport = content
                if (mime == "text/csv") createCsv.launch(name) else createJson.launch(name)
            }
        }

        @JavascriptInterface
        fun bars(dark: Boolean) {
            runOnUiThread {
                val c = WindowInsetsControllerCompat(window, web)
                c.isAppearanceLightStatusBars = !dark
                c.isAppearanceLightNavigationBars = !dark
                val col = Color.parseColor(if (dark) "#0E131A" else "#F5F7FA")
                web.setBackgroundColor(col)
                window.decorView.setBackgroundColor(col)
            }
        }
    }
}
