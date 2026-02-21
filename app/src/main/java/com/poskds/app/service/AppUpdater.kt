package com.poskds.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppUpdater {

    private const val TAG = "AppUpdater"
    private const val FIREBASE_UPDATE_URL =
        "https://poskds-4ba60-default-rtdb.asia-southeast1.firebasedatabase.app/app_update/poskds.json"
    private const val FIREBASE_LOG_URL =
        "https://poskds-4ba60-default-rtdb.asia-southeast1.firebasedatabase.app/kds_status/update_log.json"
    private const val APK_NAME = "PosKDS.apk"

    private var lastCheckedVersion = ""
    private val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.KOREA)
    private var sseThread: Thread? = null
    private var sseRunning = false
    private var currentContext: Context? = null
    private var currentVersionName: String = ""

    /** 수동 업데이트 확인 (버튼) */
    fun checkAndUpdate(context: Context, currentVersionName: String) {
        check(context, currentVersionName, silent = false)
    }

    /** 자동 업데이트 확인 (백그라운드) */
    fun checkSilent(context: Context, currentVersionName: String) {
        check(context, currentVersionName, silent = true)
    }

    /** SSE 실시간 업데이트 감지 시작 */
    fun startAutoUpdate(context: Context, versionName: String) {
        currentContext = context.applicationContext
        currentVersionName = versionName
        if (sseRunning) return
        sseRunning = true
        connectSSE()
        logRemote("자동 업데이트 SSE 시작 (현재: v$versionName)")
    }

    fun stopAutoUpdate() {
        sseRunning = false
        sseThread?.interrupt()
        sseThread = null
    }

    private fun connectSSE() {
        if (!sseRunning) return
        sseThread = kotlin.concurrent.thread {
            try {
                val conn = URL(FIREBASE_UPDATE_URL).openConnection() as HttpURLConnection
                conn.setRequestProperty("Accept", "text/event-stream")
                conn.connectTimeout = 15000
                conn.readTimeout = 0

                if (conn.responseCode != 200) {
                    conn.disconnect()
                    scheduleReconnect()
                    return@thread
                }

                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                var eventType = ""

                while (sseRunning) {
                    val line = reader.readLine() ?: break
                    when {
                        line.startsWith("event:") -> eventType = line.substringAfter("event:").trim()
                        line.startsWith("data:") -> {
                            if (eventType == "put" || eventType == "patch") {
                                handleSSEData(line.substringAfter("data:").trim())
                            }
                        }
                    }
                }

                reader.close()
                conn.disconnect()
            } catch (_: InterruptedException) {
                return@thread
            } catch (e: Exception) {
                Log.w(TAG, "SSE 에러: ${e.message}")
            }
            scheduleReconnect()
        }
    }

    private fun handleSSEData(raw: String) {
        try {
            val wrapper = JSONObject(raw)
            val data = wrapper.opt("data") ?: return
            if (data.toString() == "null") return

            val obj = if (data is JSONObject) data else JSONObject(data.toString())
            val version = obj.optString("version", "")
            val apkUrl = obj.optString("url", obj.optString("apk_url", ""))

            if (version.isEmpty() || apkUrl.isEmpty()) return
            if (version == currentVersionName) return
            if (version == lastCheckedVersion) return

            lastCheckedVersion = version
            val ctx = currentContext ?: return
            logRemote("SSE 새 버전 감지: v$version (현재: v$currentVersionName)")
            showToast(ctx, "새 버전 v$version 다운로드 중...")
            downloadApk(ctx, apkUrl, silent = false)
        } catch (e: Exception) {
            Log.w(TAG, "SSE 데이터 처리 실패: ${e.message}")
        }
    }

    private fun scheduleReconnect() {
        if (!sseRunning) return
        Thread.sleep(10_000)
        connectSSE()
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
                val apkUrl = json.optString("url", json.optString("apk_url", ""))

                if (latestVersion.isEmpty() || apkUrl.isEmpty()) {
                    if (!silent) showToast(context, "업데이트 정보 불완전")
                    return@thread
                }

                if (latestVersion == currentVersionName) {
                    if (!silent) showToast(context, "최신 버전입니다 (v$currentVersionName)")
                    return@thread
                }

                if (silent && latestVersion == lastCheckedVersion) return@thread
                lastCheckedVersion = latestVersion

                logRemote("새 버전 발견: v$latestVersion (현재: v$currentVersionName)")
                if (!silent) showToast(context, "v$latestVersion 다운로드 중...")

                downloadApk(context, apkUrl, silent)

            } catch (e: Exception) {
                logRemote("업데이트 확인 실패: ${e.javaClass.simpleName}: ${e.message}")
                if (!silent) showToast(context, "업데이트 확인 실패")
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

            // 직접 실행 시도
            try {
                context.startActivity(intent)
                logRemote("설치 인텐트 직접 실행 완료")
            } catch (_: Exception) {
                logRemote("직접 실행 실패 → 알림으로 전환")
            }

            // 알림으로도 항상 띄움 (백그라운드 대비)
            showInstallNotification(context, intent)
        } catch (e: Exception) {
            logRemote("설치 실패: ${e.javaClass.simpleName}: ${e.message}")
            showToast(context, "설치 실패: ${e.message}")
        }
    }

    private fun showInstallNotification(context: Context, installIntent: Intent) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "kds_update"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(channelId, "앱 업데이트", NotificationManager.IMPORTANCE_HIGH)
            nm.createNotificationChannel(ch)
        }
        val pi = PendingIntent.getActivity(
            context, 0, installIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("")
            .setContentText("업데이트 다운로드 완료 - 탭하여 설치")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
            .build()
        nm.notify(9999, n)
        logRemote("설치 알림 표시 완료")
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
