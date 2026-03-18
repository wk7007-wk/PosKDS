package com.poskds.app.service

import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec

/**
 * KDS → PosDelay FCM push 전송.
 * Firebase 서비스 계정으로 OAuth2 인증 → FCM v1 API로 data message 전송.
 * topic "kds_push"에 구독한 기기(PosDelay)가 수신.
 */
object FcmSender {

    private const val TAG = "FcmSender"
    private const val PROJECT_ID = "poskds-4ba60"
    private const val TOKEN_URI = "https://oauth2.googleapis.com/token"
    private const val FCM_URL = "https://fcm.googleapis.com/v1/projects/$PROJECT_ID/messages:send"
    private const val SCOPE = "https://www.googleapis.com/auth/firebase.messaging"

    private const val CLIENT_EMAIL = "firebase-adminsdk-fbsvc@poskds-4ba60.iam.gserviceaccount.com"
    private const val SA_KEY_URL = "https://poskds-4ba60-default-rtdb.asia-southeast1.firebasedatabase.app/monitor/poskds/sa_key.json"

    // 런타임 로드 — 하드코딩 제거
    @Volatile private var privateKeyPem: String? = null
    @Volatile private var keyLoaded = false

    @Volatile private var cachedToken: String? = null
    @Volatile private var tokenExpiry = 0L

    // 마지막 전송값 (동일 데이터 중복 전송 방지)
    @Volatile private var lastSentCount = -1
    @Volatile private var lastSentOrdersHash = 0

    /**
     * Firebase RTDB에서 서비스 계정 키 로드 → 메모리 캐시.
     * 앱 시작 시 1회 호출. 이후 캐시된 키 사용.
     */
    fun loadServiceAccountKey() {
        if (keyLoaded && privateKeyPem != null) return
        Thread {
            try {
                val conn = URL(SA_KEY_URL).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                if (conn.responseCode == 200) {
                    val resp = conn.inputStream.bufferedReader().readText()
                    // Firebase JSON 문자열 → 따옴표 제거 + 이스케이프 복원
                    val key = resp.trim().removeSurrounding("\"").replace("\\n", "\n")
                    if (key.contains("BEGIN PRIVATE KEY")) {
                        privateKeyPem = key
                        keyLoaded = true
                        Log.d(TAG, "SA 키 로드 성공 (Firebase RTDB)")
                    } else {
                        Log.w(TAG, "SA 키 형식 오류")
                    }
                } else {
                    Log.w(TAG, "SA 키 로드 실패: HTTP ${conn.responseCode}")
                }
                conn.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "SA 키 로드 에러: ${e.message}")
            }
        }.start()
    }

    /**
     * FCM data message 전송 (건수 변경 시 호출).
     * count 또는 orders 변경 시 전송. 백그라운드 스레드에서 호출해야 함.
     */
    fun send(count: Int, completed: Int, time: String, orders: List<Int> = emptyList()) {
        val ordersHash = orders.hashCode()
        if (count == lastSentCount && ordersHash == lastSentOrdersHash) return // 동일 데이터 스킵

        if (privateKeyPem == null) {
            // 키 아직 미로드 — 동기 로드 시도 (send는 이미 백그라운드 스레드)
            loadServiceAccountKeySync()
            if (privateKeyPem == null) {
                Log.w(TAG, "SA 키 미로드 — FCM 전송 스킵")
                return
            }
        }

        lastSentCount = count
        lastSentOrdersHash = ordersHash

        try {
            val token = getAccessToken() ?: run {
                Log.w(TAG, "OAuth2 토큰 획득 실패")
                return
            }

            val message = JSONObject().apply {
                put("message", JSONObject().apply {
                    put("topic", "kds_push")
                    put("data", JSONObject().apply {
                        put("count", count.toString())
                        put("completed", completed.toString())
                        put("time", time)
                        put("orders", orders.joinToString(","))
                        put("source", "fcm")
                    })
                    // Android: high priority for Doze delivery
                    put("android", JSONObject().apply {
                        put("priority", "high")
                    })
                })
            }

            val conn = URL(FCM_URL).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.doOutput = true
            OutputStreamWriter(conn.outputStream).use { it.write(message.toString()) }

            val code = conn.responseCode
            if (code in 200..299) {
                Log.d(TAG, "FCM 전송 성공: count=$count")
            } else {
                val err = try { conn.errorStream?.bufferedReader()?.readText() } catch (_: Exception) { "" }
                Log.w(TAG, "FCM 전송 실패: HTTP $code $err")
                // 401이면 토큰 만료 → 캐시 클리어
                if (code == 401) {
                    cachedToken = null
                    tokenExpiry = 0
                }
            }
            conn.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "FCM 전송 에러: ${e.message}")
        }
    }

    /**
     * OAuth2 access token 획득 (JWT → token exchange).
     * 토큰은 50분 캐시 (만료 60분).
     */
    private fun getAccessToken(): String? {
        val now = System.currentTimeMillis()
        cachedToken?.let { if (now < tokenExpiry) return it }

        try {
            val jwt = createJwt(now)
            val body = "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion=$jwt"

            val conn = URL(TOKEN_URI).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.doOutput = true
            OutputStreamWriter(conn.outputStream).use { it.write(body) }

            val code = conn.responseCode
            if (code != 200) {
                Log.w(TAG, "토큰 교환 실패: HTTP $code")
                conn.disconnect()
                return null
            }

            val response = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            val json = JSONObject(response)
            val token = json.getString("access_token")
            val expiresIn = json.optInt("expires_in", 3600)
            cachedToken = token
            tokenExpiry = now + (expiresIn - 600) * 1000L // 10분 전 만료
            return token
        } catch (e: Exception) {
            Log.w(TAG, "토큰 획득 에러: ${e.message}")
            return null
        }
    }

    /** 동기 키 로드 — send()가 백그라운드 스레드에서 호출하므로 안전 */
    private fun loadServiceAccountKeySync() {
        if (keyLoaded && privateKeyPem != null) return
        try {
            val conn = URL(SA_KEY_URL).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            if (conn.responseCode == 200) {
                val resp = conn.inputStream.bufferedReader().readText()
                val key = resp.trim().removeSurrounding("\"").replace("\\n", "\n")
                if (key.contains("BEGIN PRIVATE KEY")) {
                    privateKeyPem = key
                    keyLoaded = true
                    Log.d(TAG, "SA 키 동기 로드 성공")
                }
            }
            conn.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "SA 키 동기 로드 에러: ${e.message}")
        }
    }

    private fun createJwt(nowMs: Long): String {
        val nowSec = nowMs / 1000
        val expSec = nowSec + 3600

        val header = base64url("""{"alg":"RS256","typ":"JWT"}""".toByteArray())
        val claim = base64url(JSONObject().apply {
            put("iss", CLIENT_EMAIL)
            put("scope", SCOPE)
            put("aud", TOKEN_URI)
            put("iat", nowSec)
            put("exp", expSec)
        }.toString().toByteArray())

        val signInput = "$header.$claim"

        // Parse PKCS8 private key — Firebase RTDB에서 런타임 로드
        val pem = privateKeyPem ?: throw IllegalStateException("SA 키 미로드")
        val keyPem = pem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\n", "")
            .replace("\r", "")
        val keyBytes = Base64.decode(keyPem, Base64.DEFAULT)
        val keySpec = PKCS8EncodedKeySpec(keyBytes)
        val privateKey = KeyFactory.getInstance("RSA").generatePrivate(keySpec)

        // Sign
        val sig = Signature.getInstance("SHA256withRSA")
        sig.initSign(privateKey)
        sig.update(signInput.toByteArray())
        val signature = base64url(sig.sign())

        return "$signInput.$signature"
    }

    private fun base64url(data: ByteArray): String =
        Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
}
