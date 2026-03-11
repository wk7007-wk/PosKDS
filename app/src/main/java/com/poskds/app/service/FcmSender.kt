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
 *
 * 비공개 키는 Firebase DB에서 로드 (소스코드 노출 방지).
 */
object FcmSender {

    private const val TAG = "FcmSender"
    private const val PROJECT_ID = "poskds-4ba60"
    private const val TOKEN_URI = "https://oauth2.googleapis.com/token"
    private const val FCM_URL = "https://fcm.googleapis.com/v1/projects/$PROJECT_ID/messages:send"
    private const val SCOPE = "https://www.googleapis.com/auth/firebase.messaging"
    private const val FIREBASE_URL = "https://poskds-4ba60-default-rtdb.asia-southeast1.firebasedatabase.app"

    private const val CLIENT_EMAIL = "firebase-adminsdk-fbsvc@poskds-4ba60.iam.gserviceaccount.com"

    // 키는 Firebase에서 1회 로드
    @Volatile private var privateKeyPem: String? = null
    @Volatile private var keyLoaded = false

    @Volatile private var cachedToken: String? = null
    @Volatile private var tokenExpiry = 0L

    @Volatile private var lastSentCount = -1
    @Volatile private var lastSentOrdersHash = 0

    /**
     * Firebase에서 비공개 키 로드 (/secrets/fcm_key)
     */
    private fun loadPrivateKey(): String? {
        if (keyLoaded) return privateKeyPem
        try {
            val url = URL("$FIREBASE_URL/secrets/fcm_key.json")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            val response = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val key = response.trim().removeSurrounding("\"")
            if (key.contains("PRIVATE KEY")) {
                privateKeyPem = key
                keyLoaded = true
                Log.d(TAG, "FCM 키 로드 성공")
                return key
            }
        } catch (e: Exception) {
            Log.w(TAG, "FCM 키 로드 실패: ${e.message}")
        }
        return null
    }

    fun send(count: Int, completed: Int, time: String, orders: List<Int> = emptyList()) {
        val ordersHash = orders.hashCode()
        if (count == lastSentCount && ordersHash == lastSentOrdersHash) return
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

    private fun getAccessToken(): String? {
        val now = System.currentTimeMillis()
        cachedToken?.let { if (now < tokenExpiry) return it }

        try {
            val keyPem = loadPrivateKey() ?: return null
            val jwt = createJwt(now, keyPem)
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
            tokenExpiry = now + (expiresIn - 600) * 1000L
            return token
        } catch (e: Exception) {
            Log.w(TAG, "토큰 획득 에러: ${e.message}")
            return null
        }
    }

    private fun createJwt(nowMs: Long, keyPem: String): String {
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

        val cleanKey = keyPem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\n", "")
            .replace("\r", "")
            .replace("\\n", "")
        val keyBytes = Base64.decode(cleanKey, Base64.DEFAULT)
        val keySpec = PKCS8EncodedKeySpec(keyBytes)
        val privateKey = KeyFactory.getInstance("RSA").generatePrivate(keySpec)

        val sig = Signature.getInstance("SHA256withRSA")
        sig.initSign(privateKey)
        sig.update(signInput.toByteArray())
        val signature = base64url(sig.sign())

        return "$signInput.$signature"
    }

    private fun base64url(data: ByteArray): String =
        Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
}
