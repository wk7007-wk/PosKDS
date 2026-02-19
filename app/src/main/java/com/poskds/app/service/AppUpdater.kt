package com.poskds.app.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Firebase 기반 자동 업데이트.
 * - Firebase에서 최신 버전 확인 (인증 불필요, 실패 없음)
 * - GitHub Release에서 APK 다운로드 (토큰 인증)
 * - 3분마다 하트비트에서 자동 체크 + 수동 버튼
 */
object AppUpdater {

    private const val TAG = "AppUpdater"
    private const val FIREBASE_UPDATE_URL =
        "https://poskds-4ba60-default-rtdb.asia-southeast1.firebasedatabase.app/app_update/poskds.json"
    private const val APK_NAME = "PosKDS.apk"

    private var lastCheckedVersion = ""

    /** 수동 버튼용 (토스트 표시) */
    fun checkAndUpdate(context: Context, currentVersionName: String) {
        check(context, currentVersionName, silent = false)
    }

    /** 하트비트 자동 체크용 (조용히) */
    fun checkSilent(context: Context, currentVersionName: String) {
        check(context, currentVersionName, silent = true)
    }

    private fun check(context: Context, currentVersionName: String, silent: Boolean) {
        kotlin.concurrent.thread {
            try {
                val conn = URL(FIREBASE_UPDATE_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 10000

                if (conn.responseCode != 200) {
                    if (!silent) showToast(context, "업데이트 확인 실패 (HTTP ${conn.responseCode})")
                    conn.disconnect()
                    return@thread
                }

                val response = conn.inputStream.bufferedReader().readText()
                conn.disconnect()

                if (response == "null" || response.isBlank()) {
                    if (!silent) showToast(context, "업데이트 정보 없음")
                    return@thread
                }

                val json = JSONObject(response)
                val latestVersion = json.optString("version", "")
                val apkUrl = json.optString("apk_url", "")

                if (latestVersion.isEmpty() || apkUrl.isEmpty()) {
                    if (!silent) showToast(context, "업데이트 정보 불완전")
                    return@thread
                }

                if (latestVersion == currentVersionName) {
                    if (!silent) showToast(context, "최신 버전입니다 (v$currentVersionName)")
                    return@thread
                }

                // 같은 버전 반복 다운로드 방지
                if (silent && latestVersion == lastCheckedVersion) return@thread
                lastCheckedVersion = latestVersion

                Log.d(TAG, "새 버전 발견: v$latestVersion (현재: v$currentVersionName)")
                if (!silent) showToast(context, "v$latestVersion 다운로드 중...")

                downloadApk(context, apkUrl, silent)

            } catch (e: Exception) {
                Log.w(TAG, "업데이트 확인 실패: ${e.message}")
                if (!silent) showToast(context, "업데이트 확인 실패")
            }
        }
    }

    private fun downloadApk(context: Context, apkUrl: String, silent: Boolean) {
        // 앱 자체 디렉토리 사용 (권한 불필요)
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context.filesDir
        val file = File(dir, APK_NAME)
        if (file.exists()) file.delete()

        try {
            val prefs = context.getSharedPreferences("poskds_prefs", Context.MODE_PRIVATE)
            val token = prefs.getString("github_token", "") ?: ""

            // 리다이렉트 수동 처리 (GitHub API → S3 pre-signed URL)
            var downloadUrl = apkUrl
            var maxRedirects = 5
            var finalConn: HttpURLConnection? = null

            while (maxRedirects-- > 0) {
                val conn = URL(downloadUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 30000
                conn.readTimeout = 60000
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("Accept", "application/octet-stream")
                // GitHub API에만 토큰 전송
                if (downloadUrl.contains("api.github.com") && token.isNotEmpty()) {
                    conn.setRequestProperty("Authorization", "token $token")
                }

                val code = conn.responseCode
                if (code == 301 || code == 302 || code == 307) {
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (location.isNullOrEmpty()) break
                    downloadUrl = location
                    continue
                }

                if (code != 200) {
                    Log.w(TAG, "다운로드 실패 (HTTP $code)")
                    if (!silent) showToast(context, "다운로드 실패 (HTTP $code)")
                    conn.disconnect()
                    lastCheckedVersion = ""
                    return
                }

                finalConn = conn
                break
            }

            if (finalConn == null) {
                Log.w(TAG, "리다이렉트 실패")
                if (!silent) showToast(context, "다운로드 실패 (리다이렉트)")
                lastCheckedVersion = ""
                return
            }

            finalConn.inputStream.use { input ->
                file.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            finalConn.disconnect()

            Log.d(TAG, "APK 다운로드 완료: ${file.length()} bytes")
            showToast(context, "다운로드 완료, 설치 중...")
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                installApk(context, file)
            }
        } catch (e: Exception) {
            Log.w(TAG, "다운로드 실패: ${e.message}")
            if (!silent) showToast(context, "다운로드 실패: ${e.message}")
            lastCheckedVersion = "" // 재시도 허용
        }
    }

    private fun installApk(context: Context, file: File) {
        val uri = if (Build.VERSION.SDK_INT >= 24) {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } else {
            Uri.fromFile(file)
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        context.startActivity(intent)
    }

    private fun showToast(context: Context, msg: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }
}
