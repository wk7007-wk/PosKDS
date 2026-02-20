package com.poskds.app

import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.poskds.app.service.AppUpdater
import com.poskds.app.service.KdsAccessibilityService
import com.poskds.app.service.KeepAliveService
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "poskds_prefs"
        private const val KEY_KDS_PACKAGE = "kds_package"
        private const val DEFAULT_KDS_PACKAGE = "com.foodtechkorea.mate_kds"
        private const val PAGE_URL = "https://wk7007-wk.github.io/PosKDS/kds.html"
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        applyDefaults()

        webView = findViewById(R.id.webView)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        webView.addJavascriptInterface(KdsBridge(), "Android")
        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    showFallback()
                }
            }
        }
        webView.loadUrl(PAGE_URL)

        // 서비스 시작
        KeepAliveService.start(this)
        requestBatteryOptimizationExemption()
    }

    private fun applyDefaults() {
        val kds = prefs.getString(KEY_KDS_PACKAGE, "") ?: ""
        if (kds.isEmpty() || kds == packageName || !kds.contains(".")) {
            prefs.edit().putString(KEY_KDS_PACKAGE, DEFAULT_KDS_PACKAGE).apply()
        }
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                })
            }
        }
    }

    private fun showFallback() {
        val count = prefs.getInt("last_count", -1)
        val countText = if (count >= 0) count.toString() else "--"
        val accessOk = KdsAccessibilityService.isAvailable()
        val accessColor = if (accessOk) "#2ECC71" else "#E74C3C"
        val html = """
            <html><body style="background:#1A1A30;color:#E0E0EC;font-family:sans-serif;text-align:center;padding:40px">
            <h2>PosKDS</h2>
            <p style="color:#9090A8">네트워크 오류 - 오프라인 모드</p>
            <p style="font-size:64px;font-weight:bold;color:#FFF">$countText</p>
            <p style="color:$accessColor">접근성: ${if (accessOk) "ON" else "OFF"}</p>
            <p style="color:#707088;font-size:12px">접근성 서비스는 백그라운드에서 정상 동작 중</p>
            <button onclick="location.reload()" style="background:#2AC1BC;color:#FFF;border:none;padding:12px 24px;border-radius:8px;font-size:14px;margin-top:20px">재시도</button>
            </body></html>
        """.trimIndent()
        webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }

    inner class KdsBridge {
        @JavascriptInterface
        fun isAccessibilityEnabled(): Boolean = KdsAccessibilityService.isAvailable()

        @JavascriptInterface
        fun getKdsPackage(): String =
            prefs.getString(KEY_KDS_PACKAGE, DEFAULT_KDS_PACKAGE) ?: DEFAULT_KDS_PACKAGE

        @JavascriptInterface
        fun setKdsPackage(pkg: String) {
            prefs.edit().putString(KEY_KDS_PACKAGE, pkg).apply()
        }

        @JavascriptInterface
        fun getVersionName(): String =
            try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "?" }

        @JavascriptInterface
        fun checkUpdate() {
            AppUpdater.checkAndUpdate(this@MainActivity, getVersionName())
        }

        @JavascriptInterface
        fun openAccessibilitySettings() {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        @JavascriptInterface
        fun openAppSettings() {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            })
        }

        @JavascriptInterface
        fun requestDump() {
            KdsAccessibilityService.dumpRequested = true
        }

        @JavascriptInterface
        fun getInstalledApps(): String {
            val pm = packageManager
            val apps = pm.getInstalledApplications(0)
                .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
                .sortedBy { pm.getApplicationLabel(it).toString() }
            val arr = JSONArray()
            for (app in apps) {
                arr.put(JSONObject().apply {
                    put("name", pm.getApplicationLabel(app).toString())
                    put("pkg", app.packageName)
                })
            }
            return arr.toString()
        }

        @JavascriptInterface
        fun getLastCount(): Int = prefs.getInt("last_count", -1)

        @JavascriptInterface
        fun getLastUploadTime(): Long = prefs.getLong("last_upload_time", 0)
    }
}
