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

object FirebaseUploader {

    private const val TAG = "FirebaseUploader"
    private const val KEY_LOG = "log_text"
    private const val FIREBASE_BASE = "https://poskds-4ba60-default-rtdb.asia-southeast1.firebasedatabase.app"

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA)
    private val logTimeFormat = SimpleDateFormat("HH:mm:ss", Locale.KOREA)

    fun upload(prefs: SharedPreferences, count: Int, orders: List<Int> = emptyList(), completed: Int = -1) {
        kotlin.concurrent.thread {
            try {
                val now = dateFormat.format(Date())

                // 1. 건수 + 주문번호 상태 업로드
                val ordersArr = org.json.JSONArray()
                orders.forEach { ordersArr.put(it) }
                val statusJson = JSONObject().apply {
                    put("count", count)
                    put("time", now)
                    put("source", "kds")
                    put("orders", ordersArr)
                    if (completed >= 0) put("completed", completed)
                }.toString()
                firebasePut("$FIREBASE_BASE/kds_status.json", statusJson)

                // 1-b. Gist 보조 채널 동시 기록
                GistUploader.upload(count, orders)

                // 1-c. FCM push 전송 (PosDelay에 OS 레벨 push)
                FcmSender.send(count, if (completed >= 0) completed else 0, now)

                // 2. 로그 업로드 (최근 100줄)
                val logContent = try {
                    val f = File(KdsAccessibilityService.logFile)
                    if (f.exists()) {
                        f.readLines().takeLast(100).joinToString("\n")
                    } else {
                        prefs.getString(KEY_LOG, "") ?: ""
                    }
                } catch (_: Exception) {
                    prefs.getString(KEY_LOG, "") ?: ""
                }
                if (logContent.isNotEmpty()) {
                    firebasePut("$FIREBASE_BASE/kds_log.json", "\"${escapeJson(logContent)}\"")
                }

                log(prefs, "업로드 성공 (건수=$count)")

            } catch (e: Exception) {
                Log.w(TAG, "업로드 에러: ${e.message}")
                log(prefs, "업로드 에러: ${e.message}")
            }
        }
    }

    fun uploadDump(tree: String, lineCount: Int) {
        kotlin.concurrent.thread {
            try {
                val now = dateFormat.format(Date())
                val json = JSONObject().apply {
                    put("tree", tree)
                    put("time", now)
                    put("lines", lineCount)
                }.toString()
                firebasePut("$FIREBASE_BASE/kds_dump.json", json)
            } catch (e: Exception) {
                Log.w(TAG, "덤프 업로드 에러: ${e.message}")
            }
        }
    }

    fun uploadHistory(entries: org.json.JSONArray) {
        kotlin.concurrent.thread {
            try {
                val json = JSONObject().apply {
                    put("entries", entries)
                }.toString()
                firebasePut("$FIREBASE_BASE/kds_history.json", json)
            } catch (e: Exception) {
                Log.w(TAG, "이력 업로드 에러: ${e.message}")
            }
        }
    }

    private fun firebasePut(url: String, json: String) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "PUT"
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        OutputStreamWriter(conn.outputStream).use { it.write(json) }
        val code = conn.responseCode
        conn.disconnect()
        if (code !in 200..299) {
            Log.w(TAG, "Firebase PUT 실패: HTTP $code ($url)")
        }
    }

    private fun escapeJson(s: String): String =
        s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "")
            .replace("\t", "\\t")

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
