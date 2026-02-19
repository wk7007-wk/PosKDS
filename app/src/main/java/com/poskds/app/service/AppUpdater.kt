package com.poskds.app.service

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONArray
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
                val url = URL(API_URL)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json")

                if (conn.responseCode != 200) {
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

                // APK 다운로드 URL 찾기
                var apkUrl: String? = null
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    if (asset.getString("name").endsWith(".apk")) {
                        apkUrl = asset.getString("browser_download_url")
                        break
                    }
                }

                if (apkUrl == null) {
                    showToast(context, "APK를 찾을 수 없습니다")
                    return@thread
                }

                showToast(context, "v$latestTag 다운로드 중...")
                downloadAndInstall(context, apkUrl)

            } catch (e: Exception) {
                Log.w(TAG, "업데이트 확인 실패: ${e.message}")
                showToast(context, "업데이트 확인 실패")
            }
        }
    }

    private fun downloadAndInstall(context: Context, apkUrl: String) {
        // 기존 파일 삭제
        val file = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            APK_NAME
        )
        if (file.exists()) file.delete()

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle("PosKDS 업데이트")
            .setDescription("최신 버전 다운로드 중...")
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, APK_NAME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)

        val downloadId = dm.enqueue(request)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    ctx.unregisterReceiver(this)
                    installApk(ctx, file)
                }
            }
        }

        context.registerReceiver(
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            if (Build.VERSION.SDK_INT >= 33) Context.RECEIVER_EXPORTED else 0
        )
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
