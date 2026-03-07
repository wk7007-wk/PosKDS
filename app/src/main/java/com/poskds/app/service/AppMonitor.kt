package com.poskds.app.service

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

object AppMonitor {
    private const val FIREBASE_URL = "https://poskds-4ba60-default-rtdb.asia-southeast1.firebasedatabase.app"
    private const val APP = "poskds"
    private var version = ""

    fun init(context: Context) {
        version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (e: Exception) { "?" }

        val default = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            reportCrash(e)
            default?.uncaughtException(t, e)
        }
        sendHeartbeat()
    }

    private fun reportCrash(err: Throwable) {
        try {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA)
            sdf.timeZone = TimeZone.getTimeZone("Asia/Seoul")
            val json = JSONObject().apply {
                put("time", sdf.format(Date()))
                put("error", err.message ?: "unknown")
                put("stack", err.stackTraceToString().take(3000))
                put("version", version)
                put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                put("sdk", Build.VERSION.SDK_INT)
            }
            val conn = URL("$FIREBASE_URL/monitor/$APP/crashes.json").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.outputStream.write(json.toString().toByteArray())
            conn.responseCode
            conn.disconnect()
        } catch (_: Exception) {}
    }

    fun sendHeartbeat() {
        Thread {
            try {
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA)
                sdf.timeZone = TimeZone.getTimeZone("Asia/Seoul")
                val json = JSONObject().apply {
                    put("version", version)
                    put("lastActive", sdf.format(Date()))
                    put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                    put("sdk", Build.VERSION.SDK_INT)
                }
                val conn = URL("$FIREBASE_URL/monitor/$APP/status.json").openConnection() as HttpURLConnection
                conn.requestMethod = "PUT"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.outputStream.write(json.toString().toByteArray())
                conn.responseCode
                conn.disconnect()
            } catch (_: Exception) {}
        }.start()
    }
}
