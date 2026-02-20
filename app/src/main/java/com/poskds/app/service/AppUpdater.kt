package com.poskds.app.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONArray
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppUpdater {

    private const val TAG = "AppUpdater"
    private const val GITHUB_API = "https://api.github.com/repos/wk7007-wk/PosKDS/releases/latest"
    private const val FIREBASE_LOG_URL =
        "https://poskds-4ba60-default-rtdb.asia-southeast1.firebasedatabase.app/kds_status/update_log.json"
    private const val APK_NAME = "PosKDS.apk"

    private var lastCheckedVersion = ""
    private val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.KOREA)

    fun checkAndUpdate(context: Context, currentVersionName: String) {
        check(context, currentVersionName, silent = false)
    }

    fun checkSilent(context: Context, currentVersionName: String) {
        check(context, currentVersionName, silent = true)
    }

    private fun check(context: Context, currentVersionName: String, silent: Boolean) {
        kotlin.concurrent.thread {
            try {
                val conn = URL(GITHUB_API).openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json")

                if (conn.responseCode != 200) {
                    if (!silent) showToast(context, "업데이트 확인 실패 (HTTP ${conn.responseCode})")
                    conn.disconnect()
                    return@thread
                }

                val response = conn.inputStream.bufferedReader().readText()
                conn.disconnect()

                val json = org.json.JSONObject(response)
                val tagName = json.optString("tag_name", "")  // e.g. "v0220.0613"
                val latestVersion = tagName.removePrefix("v")  // "0220.0613"

                if (latestVersion.isEmpty()) {
                    if (!silent) showToast(context, "릴리즈 정보 없음")
                    return@thread
                }

                if (latestVersion == currentVersionName) {
                    if (!silent) showToast(context, "최신 버전입니다 (v$currentVersionName)")
                    return@thread
                }

                // APK URL 찾기
                val assets = json.optJSONArray("assets") ?: JSONArray()
                var apkUrl = ""
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    if (asset.getString("name").endsWith(".apk")) {
                        apkUrl = asset.getString("browser_download_url")
                        break
                    }
                }

                if (apkUrl.isEmpty()) {
                    if (!silent) showToast(context, "APK 없는 릴리즈")
                    return@thread
                }

                if (silent && latestVersion == lastCheckedVersion) return@thread
                lastCheckedVersion = latestVersion

                logRemote("새 버전 발견: v$latestVersion (현재: v$currentVersionName)")
                showToast(context, "v$latestVersion 다운로드 중...")

                downloadApk(context, apkUrl, silent)

            } catch (e: Exception) {
                logRemote("업데이트 확인 실패: ${e.javaClass.simpleName}: ${e.message}")
                if (!silent) showToast(context, "업데이트 확인 실패: ${e.message}")
            }
        }
    }

    private fun downloadApk(context: Context, apkUrl: String, silent: Boolean) {
        val file = File(context.filesDir, APK_NAME)
        if (file.exists()) file.delete()

        try {
            logRemote("다운로드 시작: $apkUrl")

            var downloadUrl = apkUrl
            var maxRedirects = 5
            var finalConn: HttpURLConnection? = null

            while (maxRedirects-- > 0) {
                val conn = URL(downloadUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 30000
                conn.readTimeout = 60000
                conn.instanceFollowRedirects = false

                val code = conn.responseCode

                if (code == 301 || code == 302 || code == 307) {
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (location.isNullOrEmpty()) break
                    downloadUrl = location
                    continue
                }

                if (code != 200) {
                    logRemote("다운로드 실패: HTTP $code")
                    if (!silent) showToast(context, "다운로드 실패 (HTTP $code)")
                    conn.disconnect()
                    lastCheckedVersion = ""
                    return
                }

                finalConn = conn
                break
            }

            if (finalConn == null) {
                logRemote("리다이렉트 실패")
                if (!silent) showToast(context, "다운로드 실패")
                lastCheckedVersion = ""
                return
            }

            finalConn.inputStream.use { input ->
                file.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            finalConn.disconnect()

            val size = file.length()
            logRemote("다운로드 완료: ${size} bytes")

            if (size < 100_000) {
                logRemote("파일 너무 작음 ($size bytes)")
                if (!silent) showToast(context, "다운로드 실패 (파일 손상)")
                file.delete()
                lastCheckedVersion = ""
                return
            }

            showToast(context, "다운로드 완료, 설치 중...")
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                installApk(context, file)
            }
        } catch (e: Exception) {
            logRemote("다운로드 예외: ${e.javaClass.simpleName}: ${e.message}")
            if (!silent) showToast(context, "다운로드 실패: ${e.message}")
            lastCheckedVersion = ""
        }
    }

    private fun installApk(context: Context, file: File) {
        try {
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
            logRemote("설치 인텐트 실행 완료")
        } catch (e: Exception) {
            logRemote("설치 실패: ${e.javaClass.simpleName}: ${e.message}")
            showToast(context, "설치 실패: ${e.message}")
        }
    }

    private fun logRemote(message: String) {
        val time = dateFormat.format(Date())
        val entry = "[$time] $message"
        Log.d(TAG, entry)

        kotlin.concurrent.thread {
            try {
                val json = "\"${entry.replace("\"", "\\\"")}\""
                val conn = URL(FIREBASE_LOG_URL).openConnection() as HttpURLConnection
                conn.requestMethod = "PUT"
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                OutputStreamWriter(conn.outputStream).use { it.write(json) }
                conn.responseCode
                conn.disconnect()
            } catch (_: Exception) {}
        }
    }

    private fun showToast(context: Context, msg: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }
}
