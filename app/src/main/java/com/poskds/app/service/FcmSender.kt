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
    private const val PRIVATE_KEY = "-----BEGIN PRIVATE KEY-----\n" +
        "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQD4f4N8RKAawho8\n" +
        "OvCp/ihUUK4jJTb+I5kkRMSMsd9lxOpK/v7cfOmxH9v2K99zy1KBlDu5Wbr/gyFH\n" +
        "Sue4qmXDp27fmo285agSJPNu7XABlOlkg+OBZSLoe5/N41K0A8J65CzVgYTN4LYP\n" +
        "qkJ9KbYQ0C9COHYbS77ZdxTJFX3qApi8c9bcs3A5qTQhJmvlBJUR4ayE40WrV+Qq\n" +
        "SU7EJWSiDIfjJP+HAhJd/P7zZoOt64Ine1v8/Fc0ZhR0hYv841XdNOaen3umUgix\n" +
        "fbCz/zDhc+w498xyGXiJRFY2q/4Wq5z6g1Zcv8cQPEVnDRGK0S0WUTetWBKFfuqZ\n" +
        "lrClgk13AgMBAAECggEAFfPBzFENqb967NYyG3pR2rzz3TP06zd+2Fbg3CL8frOK\n" +
        "FQz4u8anKFgNqO4QQ9zy0XKkYgfcvqS5ZGBoHwaijcm1QDiZi9Xn5o5wGN0N133t\n" +
        "rkz+ZJhoIIyHPft2e2OXox6UHVpfPoa63qBmVkNAi7SwcBOnz1p+Jhzgb7Ef2fOr\n" +
        "NUq/KX3g1omBBjI8ujgz7Eyq3gB6REmlrVXu3P6m3FUyravAWoSFh85dOeDw8AB3\n" +
        "OkGZq5EkDTy0sWe/O0+j9c0U57J9kMJzzbANnFt0VwDYAdJ8ipd2o/4+Tg0KOwUf\n" +
        "RWr5dWHpQpCsH86PyRAgu2GPogXEpnoLIPH9d5gnVQKBgQD9Y23aY/cJgvsSsOhX\n" +
        "AImDsTUpwHowf0XmrtsLptuEy6MRyap7gUmxkJVAkI40aql96gT4IP2KETT7c783\n" +
        "q371mRblu9jN0+HevMrb2DPBga1TjEhqGfkqYYrRGxaJLnK5toLb3EpEChf70aMs\n" +
        "yVKEDGbayZz4uepyhXFktMGQPQKBgQD7Dy5s852tYK7m1zyaixM3NL1DFfo3YphI\n" +
        "H3PTKF1Qhxq7EDIm4ytdZ7sMh9kXcKbjxjbUeaQJ5fuTLCITW/GYpYcOZ3qXIIxk\n" +
        "77r5+8iTBAPtH6FI3wj4VHp5Wtgw9qp4izQ9uwJn9rDIHUPiVyeEByOO5XtaV7hk\n" +
        "Oa529fcbwwKBgA3JkTqm4dREqkC0G5BQWSsvQ0NIU927ryQEM6sIoz0wj2jyXjJm\n" +
        "MIpW4agntXUosJxHVYni0ajnkshz3d27mSbn85UAiaV5d/rUrv0TYI2Q7stzAKW1\n" +
        "UBd8Qz9ph+pi+p8cTTaFYA0ft3peR9CyC6lfu2EAQ2hNRXKBzE+8fiPJAoGALAf/\n" +
        "lnAriUrZofbB1EDr/9SqFOf32FrcZlnN0IzVwNfRIlm20gcphdo5ffsdYfUJ8AzF\n" +
        "dQJYeLvzIV6uI0MO3jy5sRcI8xRsSw+YdVtpVA9yONZBTSyAwDzgtgPuwregMkAH\n" +
        "y4PO6jjjzFUFoN60OX2fCOLKfY/A8SMErCx7SE0CgYEA+8ASwXuB8pBkpatr6s0b\n" +
        "EZNmf2wmkAQZUDayp6FQDiX0/rjH4WkRK/6X24j3nkLY3uV5uqCzq0jcrKC2J/Xz\n" +
        "wxeynU+qi5c4auV5vtIaf3MUN920rfq/68ENe7NUsvD5m82MlsGKJGi89LSrIVcF\n" +
        "vAaP+aXRj1Ig1R40YtctPpk=\n" +
        "-----END PRIVATE KEY-----\n"

    @Volatile private var cachedToken: String? = null
    @Volatile private var tokenExpiry = 0L

    // 마지막 전송 건수 (동일 건수 중복 전송 방지)
    @Volatile private var lastSentCount = -1

    /**
     * FCM data message 전송 (건수 변경 시 호출).
     * 동일 건수 중복 전송 방지. 백그라운드 스레드에서 호출해야 함.
     */
    fun send(count: Int, completed: Int, time: String) {
        if (count == lastSentCount) return // 동일 건수 스킵
        lastSentCount = count

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

        // Parse PKCS8 private key
        val keyPem = PRIVATE_KEY
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
