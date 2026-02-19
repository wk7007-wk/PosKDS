package com.poskds.app

import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.poskds.app.service.AppUpdater
import com.poskds.app.service.KdsAccessibilityService
import com.poskds.app.service.KeepAliveService

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "poskds_prefs"
        private const val KEY_KDS_PACKAGE = "kds_package"
        private const val KEY_LAST_COUNT = "last_count"
        private const val KEY_LAST_UPLOAD_TIME = "last_upload_time"
        private const val KEY_LOG = "log_text"

        private const val DEFAULT_KDS_PACKAGE = "com.foodtechkorea.mate_kds"
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var tvCount: TextView
    private lateinit var tvLastSync: TextView
    private lateinit var tvAccessDot: TextView
    private lateinit var tvKdsDot: TextView
    private lateinit var tvKdsPackage: TextView
    private lateinit var tvLog: TextView
    private lateinit var etKdsPackage: EditText

    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshUI()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        tvCount = findViewById(R.id.tvCount)
        tvLastSync = findViewById(R.id.tvLastSync)
        tvAccessDot = findViewById(R.id.tvAccessDot)
        tvKdsDot = findViewById(R.id.tvKdsDot)
        tvKdsPackage = findViewById(R.id.tvKdsPackage)
        tvLog = findViewById(R.id.tvLog)
        etKdsPackage = findViewById(R.id.etKdsPackage)

        applyDefaults()

        // 배터리 최적화 제외 요청 (서비스 유지)
        requestBatteryOptimizationExemption()

        // 포그라운드 서비스 시작
        KeepAliveService.start(this)

        // 저장된 값 로드
        etKdsPackage.setText(prefs.getString(KEY_KDS_PACKAGE, DEFAULT_KDS_PACKAGE))

        // 버전 표시 + 업데이트
        val versionName = packageManager.getPackageInfo(packageName, 0).versionName
        findViewById<TextView>(R.id.tvVersion).text = "v$versionName"
        findViewById<TextView>(R.id.btnUpdate).setOnClickListener {
            AppUpdater.checkAndUpdate(this, versionName)
        }

        // 설정 접기/펼치기
        val layoutSettings = findViewById<LinearLayout>(R.id.layoutSettings)
        val btnToggle = findViewById<TextView>(R.id.btnToggleSettings)
        btnToggle.setOnClickListener {
            if (layoutSettings.visibility == View.GONE) {
                layoutSettings.visibility = View.VISIBLE
                btnToggle.text = "설정 ▼"
            } else {
                layoutSettings.visibility = View.GONE
                btnToggle.text = "설정 ▶"
            }
        }

        // 제한 해제 → 앱 정보 페이지
        findViewById<TextView>(R.id.btnRestrict).setOnClickListener {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }

        // 접근성 설정 버튼
        findViewById<TextView>(R.id.btnAccess).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        // KDS 앱 선택 버튼
        findViewById<TextView>(R.id.btnPickKds).setOnClickListener {
            showAppPicker()
        }

        // UI 트리 덤프 버튼
        findViewById<TextView>(R.id.btnDump).setOnClickListener {
            val svc = KdsAccessibilityService.instance
            if (svc == null) {
                Toast.makeText(this, "접근성 서비스 비활성", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val dump = svc.dumpTree()
            tvLog.text = if (dump.isNotEmpty()) dump else "트리 비어있음"
            Toast.makeText(this, "덤프 완료 (${dump.lines().size}줄)", Toast.LENGTH_SHORT).show()
        }

        // 저장 버튼
        findViewById<TextView>(R.id.btnSave).setOnClickListener {
            val kdsPackage = etKdsPackage.text.toString().trim()
            prefs.edit().putString(KEY_KDS_PACKAGE, kdsPackage).apply()
            Toast.makeText(this, "저장됨", Toast.LENGTH_SHORT).show()
            refreshUI()
        }
    }

    @android.annotation.SuppressLint("BatteryLife")
    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = android.net.Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        }
    }

    private fun applyDefaults() {
        val kds = prefs.getString(KEY_KDS_PACKAGE, "") ?: ""
        if (kds.isEmpty() || kds == packageName || !kds.contains(".")) {
            prefs.edit().putString(KEY_KDS_PACKAGE, DEFAULT_KDS_PACKAGE).apply()
        }
    }

    private fun showAppPicker() {
        val pm = packageManager
        val apps = pm.getInstalledApplications(0)
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
            .sortedBy { pm.getApplicationLabel(it).toString() }

        val names = apps.map { "${pm.getApplicationLabel(it)}  (${it.packageName})" }.toTypedArray()

        AlertDialog.Builder(this, R.style.DarkDialogTheme)
            .setTitle("KDS 앱 선택")
            .setItems(names) { _, which ->
                val selected = apps[which].packageName
                etKdsPackage.setText(selected)
                prefs.edit().putString(KEY_KDS_PACKAGE, selected).apply()
                Toast.makeText(this, "KDS: ${pm.getApplicationLabel(apps[which])}", Toast.LENGTH_SHORT).show()
                refreshUI()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        handler.post(refreshRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refreshRunnable)
    }

    private fun refreshUI() {
        // 접근성 상태
        val accessOk = KdsAccessibilityService.isAvailable()
        tvAccessDot.setTextColor(if (accessOk) 0xFF2ECC71.toInt() else 0xFFE74C3C.toInt())

        // KDS 패키지
        val kdsPackage = prefs.getString(KEY_KDS_PACKAGE, "") ?: ""
        val kdsOk = kdsPackage.isNotEmpty()
        tvKdsDot.setTextColor(if (kdsOk) 0xFF2ECC71.toInt() else 0xFF707088.toInt())
        if (kdsOk) {
            val label = try {
                val ai = packageManager.getApplicationInfo(kdsPackage, 0)
                packageManager.getApplicationLabel(ai).toString()
            } catch (_: Exception) { kdsPackage }
            tvKdsPackage.text = "  KDS: $label"
        } else {
            tvKdsPackage.text = "  KDS: 미설정"
        }

        // 건수
        val count = prefs.getInt(KEY_LAST_COUNT, -1)
        tvCount.text = if (count >= 0) count.toString() else "--"

        // 마지막 동기화
        val lastUpload = prefs.getLong(KEY_LAST_UPLOAD_TIME, 0L)
        if (lastUpload > 0) {
            val ago = (System.currentTimeMillis() - lastUpload) / 1000
            tvLastSync.text = when {
                ago < 60 -> "${ago}초 전 업로드"
                ago < 3600 -> "${ago / 60}분 전 업로드"
                else -> "${ago / 3600}시간 전"
            }
        } else {
            tvLastSync.text = "대기중..."
        }

        // 로그
        val log = prefs.getString(KEY_LOG, "") ?: ""
        tvLog.text = if (log.isNotEmpty()) log else "로그 없음"
    }
}
