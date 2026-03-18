package com.poskds.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.poskds.app.service.KeepAliveService

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "PosKDS.Boot"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON") {

            Log.d(TAG, "부팅 완료 수신: $action")
            KeepAliveService.start(context)
            Log.d(TAG, "KeepAliveService 자동 시작")
        }
    }
}
