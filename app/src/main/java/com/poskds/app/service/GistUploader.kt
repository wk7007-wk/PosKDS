package com.poskds.app.service

import android.content.SharedPreferences
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object GistUploader {

    private const val TAG = "GistUploader"
    private const val KEY_TOKEN = "github_token"
    private const val KEY_GIST_ID = "gist_id"
    private const val KEY_LOG = "log_text"
    private const val STATUS_FILE = "kds_status.json"
    private const val LOG_FILE = "kds_log.txt"
    private const val FIREBASE_URL = "https://poskds-4ba60-default-rtdb.asia-southeast1.firebasedatabase.app/kds_status.json"

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA)
    private val logTimeFormat = SimpleDateFormat("HH:mm:ss", Locale.KOREA)

    fun upload(prefs: SharedPreferences, count: Int) {
        val token = prefs.getString(KEY_TOKEN, "") ?: ""
        val gistId = prefs.getString(KEY_GIST_ID, "") ?: ""

        if (token.isEmpty() || gistId.isEmpty()) {
            log(prefs, "Gist 미설정 (토큰 또는 ID 없음)")
            return
        }

        kotlin.concurrent.thread {
            try {
                val now = dateFormat.format(Date())
                val statusContent = JSONObject().apply {
                    put("count", count)
                    put("time", now)
                    put("source", "kds")
                }.toString()

                // Firebase 실시간 업로드 (우선)
                try {
                    val fbConn = URL(FIREBASE_URL).openConnection() as HttpURLConnection
                    fbConn.requestMethod = "PUT"
                    fbConn.connectTimeout = 5000
                    fbConn.readTimeout = 5000
                    fbConn.setRequestProperty("Content-Type", "application/json")
                    fbConn.doOutput = true
                    OutputStreamWriter(fbConn.outputStream).use { it.write(statusContent) }
                    val fbCode = fbConn.responseCode
                    fbConn.disconnect()
                    if (fbCode in 200..299) {
                        Log.d(TAG, "Firebase 업로드 성공 (건수=$count)")
                    } else {
                        Log.w(TAG, "Firebase 업로드 실패 (HTTP $fbCode)")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Firebase 에러: ${e.message}")
                }

                // 로그 내용 (최근 100줄) — 접근성 서비스의 실제 로그 경로 사용
                val logContent = try {
                    val f = File(KdsAccessibilityService.logFile)
                    if (f.exists()) {
                        f.readLines().takeLast(100).joinToString("\n")
                    } else {
                        prefs.getString(KEY_LOG, "로그 없음") ?: "로그 없음"
                    }
                } catch (_: Exception) {
                    prefs.getString(KEY_LOG, "로그 없음") ?: "로그 없음"
                }

                val body = JSONObject().apply {
                    put("files", JSONObject().apply {
                        put(STATUS_FILE, JSONObject().apply {
                            put("content", statusContent)
                        })
                        put(LOG_FILE, JSONObject().apply {
                            put("content", logContent)
                        })
                    })
                }.toString()

                val url = URL("https://api.github.com/gists/$gistId")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "PATCH"
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.setRequestProperty("Authorization", "token $token")
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true

                OutputStreamWriter(conn.outputStream).use { it.write(body) }

                val code = conn.responseCode
                conn.disconnect()

                if (code in 200..299) {
                    log(prefs, "Gist 업로드 성공 (건수=$count)")
                } else {
                    log(prefs, "Gist 업로드 실패 (HTTP $code)")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Gist 업로드 에러: ${e.message}")
                log(prefs, "Gist 에러: ${e.message}")
            }
        }
    }

    private fun log(prefs: SharedPreferences, message: String) {
        val time = logTimeFormat.format(Date())
        val entry = "[$time] $message"
        Log.d(TAG, entry)

        val existing = prefs.getString(KEY_LOG, "") ?: ""
        val lines = existing.split("\n").takeLast(50)
        val updated = (lines + entry).joinToString("\n")
        prefs.edit().putString(KEY_LOG, updated).apply()
    }
}
