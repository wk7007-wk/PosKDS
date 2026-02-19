package com.poskds.app.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object AppUpdater {

    private const val TAG = "AppUpdater"
    private const val REPO = "wk7007-wk/PosKDS"
    private const val API_URL = "https://api.github.com/repos/$REPO/releases/latest"
    private const val APK_NAME = "PosKDS.apk"

    fun checkAndUpdate(context: Context, currentVersionName: String) {
        kotlin.concurrent.thread {
            try {
                // private 레포 → 토큰 인증 필요
                val prefs = context.getSharedPreferences("poskds_prefs", Context.MODE_PRIVATE)
                val token = prefs.getString("github_token", "") ?: ""

                val url = URL(API_URL)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
                if (token.isNotEmpty()) {
                    conn.setRequestProperty("Authorization", "token $token")
                }

                if (conn.responseCode != 200) {
                    showToast(context, "업데이트 확인 실패 (HTTP ${conn.responseCode})")
                    conn.disconnect()
                    return@thread
                }

                val response = conn.inputStream.bufferedReader().readText()
                conn.disconnect()

                val json = org.json.JSONObject(response)
                val latestTag = json.getString("tag_name").removePrefix("v")
                val assets = json.getJSONArray("assets")

                if (latestTag == currentVersionName) {
                    showToast(context, "최신 버전입니다 (v$currentVersionName)")
                    return@thread
                }

                // APK 다운로드 URL 찾기 (private 레포 → API URL 사용)
                var apkApiUrl: String? = null
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    if (asset.getString("name").endsWith(".apk")) {
                        apkApiUrl = asset.getString("url")  // API URL (인증 가능)
                        break
                    }
                }

                if (apkApiUrl == null) {
                    showToast(context, "APK를 찾을 수 없습니다")
                    return@thread
                }

                showToast(context, "v$latestTag 다운로드 중...")
                downloadDirect(context, apkApiUrl, token)

            } catch (e: Exception) {
                Log.w(TAG, "업데이트 확인 실패: ${e.message}")
                showToast(context, "업데이트 확인 실패")
            }
        }
    }

    private fun downloadDirect(context: Context, apiUrl: String, token: String) {
        val file = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            APK_NAME
        )
        if (file.exists()) file.delete()

        try {
            val conn = URL(apiUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 30000
            conn.readTimeout = 30000
            conn.setRequestProperty("Accept", "application/octet-stream")
            if (token.isNotEmpty()) {
                conn.setRequestProperty("Authorization", "token $token")
            }
            conn.instanceFollowRedirects = true

            if (conn.responseCode != 200) {
                showToast(context, "다운로드 실패 (HTTP ${conn.responseCode})")
                conn.disconnect()
                return
            }

            conn.inputStream.use { input ->
                file.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            conn.disconnect()

            showToast(context, "다운로드 완료, 설치 중...")
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                installApk(context, file)
            }
        } catch (e: Exception) {
            Log.w(TAG, "다운로드 실패: ${e.message}")
            showToast(context, "다운로드 실패: ${e.message}")
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
