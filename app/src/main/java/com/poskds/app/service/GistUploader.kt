package com.poskds.app.service

import android.util.Log
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * GitHub Gist에 KDS 건수를 기록 (Firebase 보조 채널).
 * Firebase 불안정 시 PosDelay가 Gist에서 건수를 교차 검증.
 * 토큰/Gist ID는 Firebase에서 1회 로드.
 */
object GistUploader {

    private const val TAG = "GistUploader"
    private const val FIREBASE_CONFIG_URL =
        "https://poskds-4ba60-default-rtdb.asia-southeast1.firebasedatabase.app/gist_config.json"

    private var gistId = ""
    private var githubToken = ""
    private var initialized = false
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA)
    private var lastUploadCount = -1
    private var lastUploadTime = 0L
    private const val MIN_UPLOAD_INTERVAL = 10_000L  // 최소 10초 간격 (rate limit 보호)

    /** Firebase에서 Gist 설정 로드 (1회) */
    fun init() {
        if (initialized) return
        kotlin.concurrent.thread {
            try {
                val conn = URL(FIREBASE_CONFIG_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                if (conn.responseCode == 200) {
                    val json = conn.inputStream.bufferedReader().readText()
                    conn.disconnect()
                    if (json != "null" && json.isNotEmpty()) {
                        val obj = JSONObject(json)
                        gistId = obj.optString("gist_id", "")
                        githubToken = obj.optString("github_token", "")
                        if (gistId.isNotEmpty() && githubToken.isNotEmpty()) {
                            initialized = true
                            Log.d(TAG, "Gist 설정 로드 완료 (id=${gistId.take(8)}...)")
                        } else {
                            Log.w(TAG, "Gist 설정 불완전 (id=${gistId.isNotEmpty()}, token=${githubToken.isNotEmpty()})")
                        }
                    }
                } else {
                    conn.disconnect()
                    Log.w(TAG, "Gist 설정 로드 실패: HTTP ${conn.responseCode}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Gist 설정 로드 에러: ${e.message}")
            }
        }
    }

    /** KDS 건수를 Gist에 업로드 (Firebase와 동시 기록) */
    fun upload(count: Int, orders: List<Int> = emptyList()) {
        if (!initialized) return
        val now = System.currentTimeMillis()
        // 같은 건수 + 최소 간격 미달 → 스킵
        if (count == lastUploadCount && now - lastUploadTime < MIN_UPLOAD_INTERVAL) return

        lastUploadCount = count
        lastUploadTime = now

        kotlin.concurrent.thread {
            try {
                val timeStr = dateFormat.format(Date())
                val ordersArr = org.json.JSONArray()
                orders.forEach { ordersArr.put(it) }

                val kdsContent = JSONObject().apply {
                    put("count", count)
                    put("time", timeStr)
                    put("source", "kds")
                    put("orders", ordersArr)
                }.toString()

                // Gist PATCH: kds_status.json 파일만 업데이트 (다른 파일 유지)
                val gistPayload = JSONObject().apply {
                    put("files", JSONObject().apply {
                        put("kds_status.json", JSONObject().apply {
                            put("content", kdsContent)
                        })
                    })
                }.toString()

                val conn = URL("https://api.github.com/gists/$gistId").openConnection() as HttpURLConnection
                conn.requestMethod = "PATCH"
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.setRequestProperty("Authorization", "token $githubToken")
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
                conn.setRequestProperty("User-Agent", "PosKDS-Android")
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                OutputStreamWriter(conn.outputStream).use { it.write(gistPayload) }

                val code = conn.responseCode
                conn.disconnect()

                if (code in 200..299) {
                    Log.d(TAG, "Gist 업로드 성공 (건수=$count)")
                } else {
                    Log.w(TAG, "Gist 업로드 실패: HTTP $code")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Gist 업로드 에러: ${e.message}")
            }
        }
    }
}
