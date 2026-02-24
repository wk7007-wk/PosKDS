package com.poskds.app.service

import android.accessibilityservice.AccessibilityService
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
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
        private const val HEARTBEAT_MS = 30 * 1000L
        private const val AUTO_DUMP_MS = 5 * 60 * 1000L // 5분마다 자동 덤프
        private const val MAX_LOG_SIZE = 500_000L // 500KB
        var logFile: String = "/sdcard/Download/PosKDS_log.txt"
            private set

        var instance: KdsAccessibilityService? = null
            private set

        @JvmField var dumpRequested = false

        private const val KEY_HISTORY = "kds_history"

        fun isAvailable(): Boolean = instance != null
    }

    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private var lastCount = -1
    private var lastOrders = listOf<Int>()
    private var lastCompleted = -1
    private var lastUploadTime = 0L
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.KOREA)
    private var eventCount = 0
    private var lastEventLogTime = 0L
    private var lastDumpTime = 0L

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            // 하트비트마다 KDS 윈도우에서 건수 재추출
            val root = findKdsRoot()
            if (root != null) {
                try {
                    var count = extractCookingCount(root)
                    val orders = extractOrderNumbers(root)
                    val completed = extractCompletedCount(root)

                    // count null 보정 (KDS 윈도우 확인됨)
                    if (count == null && orders.isNotEmpty()) {
                        count = orders.size
                        log("하트비트 건수 추출 실패 → 주문 ${orders.size}건 보정")
                    } else if (count == null && completed != null) {
                        // 완료 탭 보이지만 조리중 숫자 없음 → 0건
                        count = 0
                        log("하트비트 조리중 없음, 완료=$completed → 0건")
                    }
                    // count still null → 조리중/완료 둘 다 못 찾음 → 건수 유지
                    // 탭 건수(조리중 N) 신뢰 — 주문번호 비어도 0으로 강제하지 않음

                    if (count != null && count != lastCount) {
                        log("하트비트 건수 보정: $lastCount → $count")
                        lastCount = count
                        prefs.edit().putInt(KEY_LAST_COUNT, count).apply()
                        addHistory(count)
                    }
                    if (orders != lastOrders) {
                        log("하트비트 주문번호 보정: $lastOrders → $orders")
                        lastOrders = orders
                    }
                    if (completed != null && completed != lastCompleted) {
                        log("하트비트 완료건수: $lastCompleted → $completed")
                        lastCompleted = completed
                    }
                } catch (_: Exception) {}
                root.recycle()
            } else {
                // KDS 윈도우 미발견 (systemui 등) — 건수 변경 없이 유지
            }

            val now = System.currentTimeMillis()
            if (now - lastUploadTime >= HEARTBEAT_MS && lastCount >= 0) {
                log("하트비트 업로드 (건수=$lastCount, 주문=$lastOrders, 완료=$lastCompleted)")
                FirebaseUploader.upload(prefs, lastCount, lastOrders, lastCompleted)
                lastUploadTime = now
                prefs.edit().putLong(KEY_LAST_UPLOAD_TIME, now).apply()
            }
            handler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    override fun onServiceConnected() {
        instance = this
        // 로그 경로: 앱 자체 디렉토리
        val extDir = getExternalFilesDir(null)
        if (extDir != null) {
            logFile = "${extDir.absolutePath}/PosKDS_log.txt"
        }
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        lastCount = prefs.getInt(KEY_LAST_COUNT, -1)
        lastUploadTime = prefs.getLong(KEY_LAST_UPLOAD_TIME, 0L)

        val kdsPackage = prefs.getString(KEY_KDS_PACKAGE, "") ?: ""
        log("접근성 서비스 연결됨, KDS 패키지=$kdsPackage")
        handler.postDelayed(heartbeatRunnable, HEARTBEAT_MS)

        // 포그라운드 서비스 시작 (프로세스 유지)
        KeepAliveService.start(this)

        // Gist 보조 채널 초기화 (Firebase에서 토큰 로드)
        GistUploader.init()

        // SSE 자동 업데이트 시작
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) { "" }
        if (versionName.isNotEmpty()) {
            AppUpdater.startAutoUpdate(this, versionName)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val eventType = event.eventType
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return

        val packageName = event.packageName?.toString() ?: return
        val kdsPackage = prefs.getString(KEY_KDS_PACKAGE, "") ?: ""

        // 60초마다 이벤트 수신 현황 로그
        eventCount++
        val now = System.currentTimeMillis()
        if (now - lastEventLogTime > 60_000) {
            log("이벤트 ${eventCount}건 수신, 최근 패키지=$packageName, 대상=$kdsPackage")
            eventCount = 0
            lastEventLogTime = now
        }

        if (kdsPackage.isEmpty()) return
        if (packageName != kdsPackage) return

        val root = findKdsRoot()
        if (root == null) {
            log("KDS 윈도우 없음 (이벤트=$packageName)")
            return
        }

        try {
            // 건수 + 주문번호 추출
            var count = extractCookingCount(root)
            val orders = extractOrderNumbers(root)
            val completed = extractCompletedCount(root)

            // 추출 실패 시: orders 있으면 orders 수로 보정, 둘 다 없으면 이전 값 유지
            // (UI 전환 중 일시적 추출 실패 → 0으로 강제하면 안 됨)
            if (count == null && orders.isNotEmpty()) {
                count = orders.size
                log("건수 추출 실패 → 주문번호 수 기반 보정: ${orders.size}건")
            }
            // count=null && orders=[] → 일시적 추출 실패, 건수 변경 없음

            val countChanged = count != null && count != lastCount
            val ordersChanged = orders != lastOrders

            if (countChanged || ordersChanged) {
                if (countChanged) {
                    val prevCount = lastCount
                    log("조리중 건수 변경: $prevCount → $count")
                    lastCount = count!!
                    prefs.edit().putInt(KEY_LAST_COUNT, count).apply()
                    addHistory(count)
                }
                if (ordersChanged) {
                    log("주문번호 변경: $lastOrders → $orders")
                    lastOrders = orders
                }
                if (completed != null && completed != lastCompleted) {
                    log("완료건수 변경: $lastCompleted → $completed")
                    lastCompleted = completed
                }

                FirebaseUploader.upload(prefs, lastCount, lastOrders, lastCompleted)
                lastUploadTime = System.currentTimeMillis()
                prefs.edit().putLong(KEY_LAST_UPLOAD_TIME, lastUploadTime).apply()
            }

            // 덤프 예약 처리: 건수 추출 후 실행 (lastCount 최신 보장)
            if (dumpRequested) {
                dumpRequested = false
                val sb = StringBuilder()
                dumpNode(root, sb, 0)
                val result = sb.toString()
                log("=== KDS UI 트리 덤프 (${result.lines().size}줄) ===\n$result\n=== 덤프 끝 ===")
                FirebaseUploader.upload(prefs, lastCount)
            }

            // 5분마다 자동 덤프
            if (now - lastDumpTime >= AUTO_DUMP_MS) {
                lastDumpTime = now
                val sb = StringBuilder()
                dumpNode(root, sb, 0)
                val result = sb.toString()
                FirebaseUploader.uploadDump(result, result.lines().size)
                log("자동 덤프 완료 (${result.lines().size}줄)")
            }
        } catch (e: Exception) {
            log("노드 탐색 실패: ${e.message}")
        } finally {
            root.recycle()
        }
    }

    /** KDS 패키지의 윈도우 루트 노드 반환 (active window가 아닌 경우 windows에서 탐색) */
    private fun findKdsRoot(): AccessibilityNodeInfo? {
        val kdsPackage = prefs.getString(KEY_KDS_PACKAGE, "") ?: ""
        if (kdsPackage.isEmpty()) return null

        // 1. Active window 시도
        val activeRoot = try { rootInActiveWindow } catch (_: Exception) { null }
        if (activeRoot != null) {
            if (activeRoot.packageName?.toString() == kdsPackage) return activeRoot
            activeRoot.recycle()
        }

        // 2. 모든 윈도우 탐색 (flagRetrieveInteractiveWindows 필요)
        try {
            for (window in windows) {
                val root = window.root ?: continue
                if (root.packageName?.toString() == kdsPackage) return root
                root.recycle()
            }
        } catch (_: Exception) {}

        return null
    }

    /** KDS "완료" 탭에서 완료 건수 추출 (조리완료 버튼과 구분) */
    private fun extractCompletedCount(root: AccessibilityNodeInfo): Int? {
        val nodes = root.findAccessibilityNodeInfosByText("완료")
        if (nodes != null && nodes.isNotEmpty()) {
            for (node in nodes) {
                val text = node.text?.toString() ?: ""
                val desc = node.contentDescription?.toString() ?: ""
                // "완료 35", "완료\n35" 패턴 (탭 헤더) — "조리완료" 버튼은 숫자 없으므로 제외
                val match = Regex("(?<!조리)완료[\\s\\n]*(\\d+)").find(text)
                    ?: Regex("(?<!조리)완료[\\s\\n]*(\\d+)").find(desc)
                if (match != null) {
                    return match.groupValues[1].toIntOrNull()
                }
            }
            // "조리완료" 버튼만 찾았을 수 있음 → null (탭 확인 불가)
            return null
        }
        return null
    }

    private fun extractCookingCount(root: AccessibilityNodeInfo): Int? {
        // 방법1: "조리중" 텍스트가 포함된 노드에서 숫자 추출
        val nodes = root.findAccessibilityNodeInfosByText("조리중")
        if (nodes != null && nodes.isNotEmpty()) {
            for (node in nodes) {
                val text = node.text?.toString() ?: ""
                val desc = node.contentDescription?.toString() ?: ""
                // "조리중 3", "조리중\n3", "조리중3" 패턴 (개행 포함)
                val match = Regex("조리중[\\s\\n]*(\\d+)").find(text)
                    ?: Regex("조리중[\\s\\n]*(\\d+)").find(desc)
                if (match != null) {
                    return match.groupValues[1].toIntOrNull()
                }
            }

            // "조리중" 텍스트는 있지만 숫자 없음 → 0건
            return 0
        }

        // 방법3: 전체 트리 탐색
        val treeResult = findCookingCountInTree(root)
        if (treeResult != null) return treeResult

        // 방법4: "조리할 주문이 없습니다" → 0건
        val noOrderNodes = root.findAccessibilityNodeInfosByText("조리할 주문이 없습니다")
        if (noOrderNodes != null && noOrderNodes.isNotEmpty()) return 0

        // 방법5: "주문수" 뒤 숫자 추출 (메뉴별 수량 화면)
        return findOrderCountInTree(root)
    }

    private fun findOrderCountInTree(node: AccessibilityNodeInfo, depth: Int = 0): Int? {
        if (depth > 15) return null
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val combined = "$text $desc"
        val match = Regex("주문수[\\s\\n]*(\\d+)").find(combined)
        if (match != null) return match.groupValues[1].toIntOrNull()
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findOrderCountInTree(child, depth + 1)
            child.recycle()
            if (result != null) return result
        }
        return null
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

    /** KDS UI 트리에서 주문번호 추출 (#0010 → 10) */
    private fun extractOrderNumbers(root: AccessibilityNodeInfo): List<Int> {
        val numbers = mutableListOf<Int>()
        collectOrderNumbers(root, numbers, 0)
        return numbers.distinct().sorted()
    }

    private fun collectOrderNumbers(node: AccessibilityNodeInfo, result: MutableList<Int>, depth: Int) {
        if (depth > 15) return
        val desc = node.contentDescription?.toString() ?: ""
        // "#0010" 패턴 — 앞 0 제거하여 순수 숫자 추출
        val match = Regex("^#(\\d+)$").find(desc.trim())
        if (match != null) {
            val num = match.groupValues[1].toIntOrNull()
            if (num != null && num > 0) result.add(num)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectOrderNumbers(child, result, depth + 1)
            child.recycle()
        }
    }

    /** KDS 앱의 UI 트리를 텍스트로 덤프 (디버깅용) */
    fun dumpTree(): String {
        val root = try { rootInActiveWindow } catch (_: Exception) { null }
            ?: return "root 없음"
        val sb = StringBuilder()
        dumpNode(root, sb, 0)
        root.recycle()
        val result = sb.toString()
        // 덤프 결과를 로그에 저장
        log("=== UI 트리 덤프 (${result.lines().size}줄) ===\n$result\n=== 덤프 끝 ===")
        // 즉시 Gist 업로드 (원격에서 확인 가능)
        if (::prefs.isInitialized) {
            FirebaseUploader.upload(prefs, lastCount)
        }
        return result
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

    private fun addHistory(count: Int) {
        try {
            val historyJson = prefs.getString(KEY_HISTORY, "[]") ?: "[]"
            val arr = try { JSONArray(historyJson) } catch (_: Exception) { JSONArray() }
            arr.put(JSONObject().apply {
                put("time", System.currentTimeMillis())
                put("count", count)
            })
            // 최근 100건만 유지
            while (arr.length() > 100) arr.remove(0)
            prefs.edit().putString(KEY_HISTORY, arr.toString()).apply()
            FirebaseUploader.uploadHistory(arr)
        } catch (e: Exception) {
            log("이력 기록 실패: ${e.message}")
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
            val file = File(logFile)
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
