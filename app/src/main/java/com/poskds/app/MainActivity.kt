package com.poskds.app

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.poskds.app.service.AppUpdater
import com.poskds.app.service.KdsAccessibilityService

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "poskds_prefs"
        private const val KEY_TOKEN = "github_token"
        private const val KEY_GIST_ID = "gist_id"
        private const val KEY_KDS_PACKAGE = "kds_package"
        private const val KEY_LAST_COUNT = "last_count"
        private const val KEY_LAST_UPLOAD_TIME = "last_upload_time"
        private const val KEY_LOG = "log_text"
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var tvCount: TextView
    private lateinit var tvLastSync: TextView
    private lateinit var tvAccessDot: TextView
    private lateinit var tvGistDot: TextView
    private lateinit var tvGistStatus: TextView
    private lateinit var tvKdsDot: TextView
    private lateinit var tvKdsPackage: TextView
    private lateinit var tvLog: TextView
    private lateinit var etToken: EditText
    private lateinit var etGistId: EditText
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
        tvGistDot = findViewById(R.id.tvGistDot)
        tvGistStatus = findViewById(R.id.tvGistStatus)
        tvKdsDot = findViewById(R.id.tvKdsDot)
        tvKdsPackage = findViewById(R.id.tvKdsPackage)
        tvLog = findViewById(R.id.tvLog)
        etToken = findViewById(R.id.etToken)
        etGistId = findViewById(R.id.etGistId)
        etKdsPackage = findViewById(R.id.etKdsPackage)

        // 저장된 값 로드
        etToken.setText(prefs.getString(KEY_TOKEN, ""))
        etGistId.setText(prefs.getString(KEY_GIST_ID, ""))
        etKdsPackage.setText(prefs.getString(KEY_KDS_PACKAGE, "com.foodtechkorea.mate_order"))

        // 버전 표시 + 업데이트
        val versionName = packageManager.getPackageInfo(packageName, 0).versionName
        findViewById<TextView>(R.id.tvVersion).text = "v$versionName"
        findViewById<TextView>(R.id.btnUpdate).setOnClickListener {
            AppUpdater.checkAndUpdate(this, versionName)
        }

        // 접근성 설정 버튼
        findViewById<TextView>(R.id.btnAccess).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        // 저장 버튼
        findViewById<TextView>(R.id.btnSave).setOnClickListener {
            val token = etToken.text.toString().trim()
            val gistId = etGistId.text.toString().trim()
            val kdsPackage = etKdsPackage.text.toString().trim()

            prefs.edit()
                .putString(KEY_TOKEN, token)
                .putString(KEY_GIST_ID, gistId)
                .putString(KEY_KDS_PACKAGE, kdsPackage)
                .apply()

            Toast.makeText(this, "저장됨", Toast.LENGTH_SHORT).show()
            refreshUI()
        }
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

        // Gist 상태
        val token = prefs.getString(KEY_TOKEN, "") ?: ""
        val gistId = prefs.getString(KEY_GIST_ID, "") ?: ""
        val gistOk = token.isNotEmpty() && gistId.isNotEmpty()
        tvGistDot.setTextColor(if (gistOk) 0xFF2ECC71.toInt() else 0xFFE74C3C.toInt())
        tvGistStatus.text = if (gistOk) "설정됨" else "미설정"

        // KDS 패키지
        val kdsPackage = prefs.getString(KEY_KDS_PACKAGE, "") ?: ""
        val kdsOk = kdsPackage.isNotEmpty()
        tvKdsDot.setTextColor(if (kdsOk) 0xFF2ECC71.toInt() else 0xFF707088.toInt())
        tvKdsPackage.text = if (kdsOk) "  KDS: $kdsPackage" else "  KDS: 미설정"

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
