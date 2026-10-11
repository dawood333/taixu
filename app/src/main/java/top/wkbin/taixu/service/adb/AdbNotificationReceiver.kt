package top.wkbin.taixu.service.adb

import org.koin.core.component.inject
import org.koin.core.component.KoinComponent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import top.wkbin.taixu.core.datastore.RuntimePreferences
import top.wkbin.taixu.runtime.bridge.adb.EmbeddedAdbManager

/**
 * 接收来自系统通知栏的无线 ADB 操作：
 * 1. 提取 RemoteInput 输入的 6 位配对码并异步发起 TLS + SPAKE2 配对与连接；
 * 2. 处理直接连接、断开连接与关闭通知请求。
 */
class AdbNotificationReceiver : BroadcastReceiver(), KoinComponent {

    val embeddedAdbManager: EmbeddedAdbManager by inject()

    val adbNotificationManager: AdbNotificationManager by inject()

    val preferences: RuntimePreferences by inject()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AdbNotificationManager.ACTION_PAIR_INPUT -> {
                val pairingCode = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(AdbNotificationManager.KEY_PAIRING_CODE)
                    ?.toString()
                    ?.trim()

                if (pairingCode.isNullOrBlank() || !pairingCode.matches(Regex("\\d{6}"))) {
                    adbNotificationManager.showFailed("Invalid pairing code: enter exactly 6 digits and try again")
                    return
                }

                // 立即刷新为配对中状态（防止通知栏 RemoteInput 转圈死锁）
                adbNotificationManager.showPairingInProgress()
                val pendingResult = goAsync()
                scope.launch {
                    try {
                        Log.i(TAG, "Pairing code received from the notification shade; starting Wireless ADB pairing…")
                        val result = embeddedAdbManager.pair(pairingCode)
                        result.fold(
                            onSuccess = {
                                Log.i(TAG, "Wireless ADB pairing from the notification shade succeeded")
                                // EmbeddedAdbManager 会自动切入 Connected 状态，并通过 StateFlow 触发 showConnected
                            },
                            onFailure = { error ->
                                Log.w(TAG, "Wireless ADB pairing from the notification shade failed", error)
                                adbNotificationManager.showFailed(error.message ?: "Wireless ADB pairing failed. Check the system pairing code and try again.")
                            },
                        )
                    } finally {
                        pendingResult.finish()
                    }
                }
            }

            AdbNotificationManager.ACTION_CONNECT -> {
                adbNotificationManager.showConnectingInProgress()
                val pendingResult = goAsync()
                scope.launch {
                    try {
                        Log.i(TAG, "Starting a direct Wireless ADB connection from the notification shade…")
                        val result = embeddedAdbManager.connect()
                        result.fold(
                            onSuccess = {
                                Log.i(TAG, "Wireless ADB connection from the notification shade succeeded")
                            },
                            onFailure = { error ->
                                Log.w(TAG, "Wireless ADB connection from the notification shade failed", error)
                                adbNotificationManager.showFailed(error.message ?: "Wireless ADB connection failed. Check the port or pair again.")
                            },
                        )
                    } finally {
                        pendingResult.finish()
                    }
                }
            }

            AdbNotificationManager.ACTION_DISCONNECT -> {
                Log.i(TAG, "Wireless ADB disconnection requested from the notification shade")
                embeddedAdbManager.disconnect()
                adbNotificationManager.showPairingPrompt()
            }

            AdbNotificationManager.ACTION_DISMISS -> {
                adbNotificationManager.dismiss()
                scope.launch { preferences.setAdbNotificationEnabled(false) }
            }
        }
    }

    private companion object {
        const val TAG = "AdbNotifReceiver"
    }
}
