package com.poskds.app.service

import android.accessibilityservice.AccessibilityService
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.io.FileWriter
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
        private const val HEARTBEAT_MS = 3 * 60 * 1000L
        private const val LOG_FILE = "/sdcard/Download/PosKDS_log.txt"
        private const val MAX_LOG_SIZE = 500_000L // 500KB

        var instance: KdsAccessibilityService? = null
            private set

        fun isAvailable(): Boolean = instance != null
    }

    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private var lastCount = -1
    private var lastUploadTime = 0L
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.KOREA)
    private var eventCount = 0
    private var lastEventLogTime = 0L

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

        val kdsPackage = prefs.getString(KEY_KDS_PACKAGE, "") ?: ""
        log("접근성 서비스 연결됨, KDS 패키지=$kdsPackage")
        handler.postDelayed(heartbeatRunnable, HEARTBEAT_MS)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val eventType = event.eventType
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return

        val packageName = event.packageName?.toString() ?: return
        val kdsPackage = prefs.getString(KEY_KDS_PACKAGE, "") ?: ""

        // 10초마다 이벤트 수신 현황 로그
        eventCount++
        val now = System.currentTimeMillis()
        if (now - lastEventLogTime > 10_000) {
            log("이벤트 ${eventCount}건 수신, 최근 패키지=$packageName, 대상=$kdsPackage")
            eventCount = 0
            lastEventLogTime = now
        }

        if (kdsPackage.isEmpty()) return
        if (packageName != kdsPackage) return

        val root = try { rootInActiveWindow } catch (_: Exception) { null }
        if (root == null) {
            log("root 노드 없음 (패키지=$packageName)")
            return
        }

        try {
            // 먼저 전체 트리 덤프 (첫 감지 또는 5분마다)
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
            log("노드 탐색 실패: ${e.message}")
        } finally {
            root.recycle()
        }
    }

    private fun extractCookingCount(root: AccessibilityNodeInfo): Int? {
        // 방법1: "조리중" 텍스트가 포함된 노드에서 숫자 추출
        val nodes = root.findAccessibilityNodeInfosByText("조리중")
        if (nodes != null && nodes.isNotEmpty()) {
            for (node in nodes) {
                val text = node.text?.toString() ?: ""
                val desc = node.contentDescription?.toString() ?: ""
                // "조리중 3", "조리중3", "조리중 ③" 패턴
                val match = Regex("조리중\\s*(\\d+)").find(text)
                    ?: Regex("조리중\\s*(\\d+)").find(desc)
                if (match != null) {
                    return match.groupValues[1].toIntOrNull()
                }
            }

            // 방법2: "조리중" 노드의 형제/자식 노드에서 숫자 찾기
            for (node in nodes) {
                val parent = node.parent ?: continue
                val count = findNumberInSiblings(parent)
                parent.recycle()
                if (count != null) return count
            }
        }

        // 방법3: 전체 트리 탐색 + 디버깅 덤프
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
        val desc = node.contentDescription?.toString() ?: ""

        val match = Regex("조리중\\s*(\\d+)").find(text)
            ?: Regex("조리중\\s*(\\d+)").find(desc)
        if (match != null) return match.groupValues[1].toIntOrNull()

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findCookingCountInTree(child, depth + 1)
            child.recycle()
            if (result != null) return result
        }
        return null
    }

    /** KDS 앱의 UI 트리를 텍스트로 덤프 (디버깅용) */
    fun dumpTree(): String {
        val root = try { rootInActiveWindow } catch (_: Exception) { null }
            ?: return "root 없음"
        val sb = StringBuilder()
        dumpNode(root, sb, 0)
        root.recycle()
        return sb.toString()
    }

    private fun dumpNode(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int) {
        if (depth > 10) return
        val indent = "  ".repeat(depth)
        val cls = node.className?.toString()?.substringAfterLast('.') ?: "?"
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val id = node.viewIdResourceName ?: ""

        if (text.isNotEmpty() || desc.isNotEmpty() || id.isNotEmpty()) {
            sb.append("$indent[$cls] ")
            if (id.isNotEmpty()) sb.append("id=$id ")
            if (text.isNotEmpty()) sb.append("t=\"$text\" ")
            if (desc.isNotEmpty()) sb.append("d=\"$desc\" ")
            sb.append("\n")
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            dumpNode(child, sb, depth + 1)
            child.recycle()
        }
    }

    private fun log(message: String) {
        val time = dateFormat.format(Date())
        val entry = "[$time] $message"
        Log.d(TAG, entry)

        // SharedPreferences 로그
        val existing = prefs.getString(KEY_LOG, "") ?: ""
        val lines = existing.split("\n").takeLast(50)
        val updated = (lines + entry).joinToString("\n")
        prefs.edit().putString(KEY_LOG, updated).apply()

        // 파일 로그
        try {
            val file = File(LOG_FILE)
            if (file.length() > MAX_LOG_SIZE) {
                val keep = file.readText().takeLast(MAX_LOG_SIZE.toInt() / 2)
                file.writeText(keep)
            }
            FileWriter(file, true).use { it.write("$entry\n") }
        } catch (_: Exception) {}
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
