package com.poskds.app.service

import android.accessibilityservice.AccessibilityService
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class KdsAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "KdsAccessibility"
        private const val PREFS_NAME = "poskds_prefs"
        private const val KEY_KDS_PACKAGE = "kds_package"
        private const val KEY_LAST_COUNT = "last_count"
        private const val KEY_LAST_UPLOAD_TIME = "last_upload_time"
        private const val KEY_LOG = "log_text"
        private const val HEARTBEAT_MS = 3 * 60 * 1000L // 3분

        var instance: KdsAccessibilityService? = null
            private set

        fun isAvailable(): Boolean = instance != null
    }

    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private var lastCount = -1
    private var lastUploadTime = 0L
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.KOREA)

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            if (now - lastUploadTime >= HEARTBEAT_MS && lastCount >= 0) {
                log("하트비트 업로드 (건수=$lastCount)")
                GistUploader.upload(prefs, lastCount)
                lastUploadTime = now
                prefs.edit().putLong(KEY_LAST_UPLOAD_TIME, now).apply()
            }
            handler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    override fun onServiceConnected() {
        instance = this
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        lastCount = prefs.getInt(KEY_LAST_COUNT, -1)
        lastUploadTime = prefs.getLong(KEY_LAST_UPLOAD_TIME, 0L)
        log("접근성 서비스 연결됨")
        handler.postDelayed(heartbeatRunnable, HEARTBEAT_MS)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val eventType = event.eventType
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return

        val packageName = event.packageName?.toString() ?: return
        val kdsPackage = prefs.getString(KEY_KDS_PACKAGE, "") ?: ""

        if (kdsPackage.isEmpty() || packageName != kdsPackage) return

        val root = rootInActiveWindow ?: return

        try {
            val count = extractCookingCount(root)
            if (count != null && count != lastCount) {
                log("조리중 건수 변경: $lastCount → $count")
                lastCount = count
                prefs.edit().putInt(KEY_LAST_COUNT, count).apply()

                GistUploader.upload(prefs, count)
                lastUploadTime = System.currentTimeMillis()
                prefs.edit().putLong(KEY_LAST_UPLOAD_TIME, lastUploadTime).apply()
            }
        } catch (e: Exception) {
            Log.w(TAG, "노드 탐색 실패: ${e.message}")
        } finally {
            root.recycle()
        }
    }

    private fun extractCookingCount(root: AccessibilityNodeInfo): Int? {
        // 방법1: "조리중" 텍스트가 포함된 노드에서 숫자 추출
        // KDS 화면: "조리중 3" 또는 탭에 "조리중" + 별도 뱃지 "3"
        val nodes = root.findAccessibilityNodeInfosByText("조리중")
        if (nodes != null && nodes.isNotEmpty()) {
            for (node in nodes) {
                val text = node.text?.toString() ?: ""
                // "조리중 3" 또는 "조리중3" 패턴
                val match = Regex("조리중\\s*(\\d+)").find(text)
                if (match != null) {
                    return match.groupValues[1].toIntOrNull()
                }
                // contentDescription에서도 찾기
                val desc = node.contentDescription?.toString() ?: ""
                val matchDesc = Regex("조리중\\s*(\\d+)").find(desc)
                if (matchDesc != null) {
                    return matchDesc.groupValues[1].toIntOrNull()
                }
            }

            // 방법2: "조리중" 노드의 형제/자식 노드에서 숫자만 있는 노드 찾기
            for (node in nodes) {
                val parent = node.parent ?: continue
                val count = findNumberInSiblings(parent)
                if (count != null) return count
                parent.recycle()
            }
        }

        // 방법3: 전체 트리에서 "조리중" 관련 텍스트 탐색
        return findCookingCountInTree(root)
    }

    private fun findNumberInSiblings(parent: AccessibilityNodeInfo): Int? {
        for (i in 0 until parent.childCount) {
            val child = parent.getChild(i) ?: continue
            val text = child.text?.toString()?.trim() ?: ""
            if (text.matches(Regex("\\d+"))) {
                val num = text.toIntOrNull()
                child.recycle()
                if (num != null && num in 0..99) return num
            }
            child.recycle()
        }
        return null
    }

    private fun findCookingCountInTree(node: AccessibilityNodeInfo, depth: Int = 0): Int? {
        if (depth > 15) return null

        val text = node.text?.toString() ?: ""
        val match = Regex("조리중\\s*(\\d+)").find(text)
        if (match != null) return match.groupValues[1].toIntOrNull()

        val desc = node.contentDescription?.toString() ?: ""
        val matchDesc = Regex("조리중\\s*(\\d+)").find(desc)
        if (matchDesc != null) return matchDesc.groupValues[1].toIntOrNull()

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findCookingCountInTree(child, depth + 1)
            child.recycle()
            if (result != null) return result
        }
        return null
    }

    private fun log(message: String) {
        val time = dateFormat.format(Date())
        val entry = "[$time] $message"
        Log.d(TAG, entry)

        val existing = prefs.getString(KEY_LOG, "") ?: ""
        val lines = existing.split("\n").takeLast(50)
        val updated = (lines + entry).joinToString("\n")
        prefs.edit().putString(KEY_LOG, updated).apply()
    }

    override fun onInterrupt() {
        log("접근성 서비스 중단됨")
        instance = null
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(heartbeatRunnable)
        instance = null
    }
}
