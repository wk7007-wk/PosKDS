package com.poskds.app.service

import android.util.Log
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * GitHub endpoints.json is the address book. Live PUT uses sets.factory from that book.
 * Gist kds_status.json is 2nd data, not the book.
 */
object FactoryAddressBook {
    private const val TAG = "FactoryAddressBook"
    const val ADDRESS_BOOK_PRIMARY = "https://wk7007-wk.github.io/bbq-dashboard/updates/endpoints.json"
    const val ADDRESS_BOOK_FALLBACK = "https://gist.githubusercontent.com/wk7007-wk/a67e5de3271d6d0716b276dc6a8391cb/raw/endpoints.json"
    private const val GIST_KDS_2ND = "https://gist.githubusercontent.com/wk7007-wk/a67e5de3271d6d0716b276dc6a8391cb/raw/kds_status.json"
    private const val REFRESH_MS = 300_000L
    private val watchStarted = AtomicBoolean(false)

    @Volatile var wanBase: String = ""
        private set
    @Volatile var wanHttps: String = ""
        private set
    @Volatile var siteLanBase: String = ""
        private set
    @Volatile var magicBase: String = ""
        private set
    @Volatile var tsBase: String = ""
        private set

    fun factoryPutUrls(name: String): List<String> {
        val file = name.trim('/').ifBlank { "kds_status.json" }
        val out = linkedSetOf<String>()
        for (raw in listOf(siteLanBase, wanBase, wanHttps, magicBase, tsBase)) {
            val base = raw.trim().trimEnd('/')
            if (!base.startsWith("http")) continue
            if (base.contains("127.0.0.1") || base.contains("localhost")) continue
            out.add("$base/$file")
        }
        return out.toList()
    }

    fun kdsSecondUrl(): String = GIST_KDS_2ND

    fun applyJson(text: String): Boolean {
        return try {
            val sets = JSONObject(text).optJSONObject("sets") ?: return false
            val factory = sets.optJSONObject("factory") ?: return false
            fun take(key: String, current: String): String {
                val v = factory.optString(key).trim().trimEnd('/')
                return if (v.startsWith("http")) v else current
            }
            wanBase = take("wan_base", wanBase)
            wanHttps = take("wan_https", wanHttps)
            siteLanBase = take("site_lan_base", siteLanBase)
            magicBase = take("magic_base", magicBase).ifBlank { take("pages_base", magicBase) }
            tsBase = take("ts_base", tsBase).ifBlank { take("pages_base_http", tsBase) }
            factoryPutUrls("kds_status.json").isNotEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "apply fail: ${e.message}")
            false
        }
    }

    fun refresh(): Boolean {
        for (url in listOf(ADDRESS_BOOK_PRIMARY, ADDRESS_BOOK_FALLBACK)) {
            val text = getText(url) ?: continue
            if (applyJson(text)) {
                Log.i(TAG, "book $url wan=$wanBase lan=$siteLanBase")
                return true
            }
        }
        return false
    }

    fun startWatch() {
        if (!watchStarted.compareAndSet(false, true)) return
        Thread {
            while (true) {
                try {
                    refresh()
                } catch (e: Exception) {
                    Log.w(TAG, "watch: ${e.message}")
                }
                try {
                    Thread.sleep(REFRESH_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }.apply {
            isDaemon = true
            name = "KdsAddressBook"
            start()
        }
    }

    fun putKdsStatus(json: String): Boolean {
        if (factoryPutUrls("kds_status.json").isEmpty()) refresh()
        var lastCode = 0
        for (url in factoryPutUrls("kds_status.json")) {
            val code = put(url, json)
            lastCode = code
            if (code in 200..299) {
                Log.i(TAG, "factory PUT ok $code")
                return true
            }
            Log.w(TAG, "factory PUT $code $url")
        }
        return lastCode in 200..299
    }

    private fun put(url: String, json: String): Int {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "PUT"
                connectTimeout = 5000
                readTimeout = 5000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "token grok-ops")
                setRequestProperty("X-Write-Token", "storebot-bus")
            }
            OutputStreamWriter(conn.outputStream).use { it.write(json) }
            conn.responseCode
        } catch (e: Exception) {
            Log.w(TAG, "PUT ${e.message}")
            -1
        } finally {
            conn?.disconnect()
        }
    }

    private fun getText(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                useCaches = false
                setRequestProperty("Cache-Control", "no-cache")
            }
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "GET ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }
}
