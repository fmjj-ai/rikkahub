package me.rerere.rikkahub.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.rerere.rikkahub.CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import org.koin.android.ext.android.inject
import kotlin.uuid.Uuid

private const val TAG = "ChatGenerationService"

class ChatGenerationService : Service() {

    companion object {
        const val ACTION_STOP = "me.rerere.rikkahub.action.CHAT_GENERATION_STOP"
        const val NOTIFICATION_ID = 2002
        private const val ACTION_ACQUIRE = "me.rerere.rikkahub.action.CHAT_GENERATION_ACQUIRE"
        private const val ACTION_RELEASE = "me.rerere.rikkahub.action.CHAT_GENERATION_RELEASE"
        private const val EXTRA_GENERATION_ID = "generation_id"
        private const val EXTRA_CONVERSATION_ID = "conversation_id"
        private const val WAKELOCK_TIMEOUT_MS = 15 * 60 * 1000L
        private const val WAKELOCK_RENEW_MS = 10 * 60 * 1000L

        fun acquire(context: Context, generationId: Uuid, conversationId: Uuid): Boolean = runCatching {
            ContextCompat.startForegroundService(context, Intent(context, ChatGenerationService::class.java).apply {
                action = ACTION_ACQUIRE
                putExtra(EXTRA_GENERATION_ID, generationId.toString())
                putExtra(EXTRA_CONVERSATION_ID, conversationId.toString())
            })
            true
        }.onFailure { Log.w(TAG, "启动聊天前台服务失败", it) }.getOrDefault(false)

        fun release(context: Context, generationId: Uuid) {
            runCatching {
                context.startService(Intent(context, ChatGenerationService::class.java).apply {
                    action = ACTION_RELEASE
                    putExtra(EXTRA_GENERATION_ID, generationId.toString())
                })
            }.onFailure { Log.w(TAG, "释放聊天前台服务租约失败", it) }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val chatService: ChatService by inject()
    private val activeGenerations = GenerationLeaseRegistry()
    private var isForeground = false
    private var wakeLockJob: Job? = null
    private val wakeLock: PowerManager.WakeLock by lazy {
        (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rikkahub:chat-generation")
            .apply { setReferenceCounted(false) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ACQUIRE -> {
                val generationId = intent.getStringExtra(EXTRA_GENERATION_ID)
                val conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID)
                if (generationId == null || conversationId == null) {
                    stopSelf(startId)
                } else {
                    activeGenerations.acquire(generationId, conversationId)
                    if (startForegroundCompat()) {
                        ensureWakeLock()
                    } else {
                        // 前台服务不可用时不擅自取消原聊天；仍可在应用前台继续生成。
                        activeGenerations.clear()
                        stopSelf()
                    }
                }
            }
            ACTION_RELEASE -> {
                intent.getStringExtra(EXTRA_GENERATION_ID)?.let(activeGenerations::release)
                if (activeGenerations.isEmpty) {
                    releaseWakeLock()
                    // 仅在没有更新的启动请求时停止，避免把队列中的 ACQUIRE 一并终止。
                    stopSelf(startId)
                } else {
                    startForegroundCompat()
                }
            }
            ACTION_STOP -> {
                chatService.cancelBackgroundGenerations(activeGenerations.generationIds)
                stopService()
            }
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        activeGenerations.clear()
        releaseWakeLock()
        if (isForeground) stopForeground(STOP_FOREGROUND_REMOVE)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // 系统超时后只剩数秒收尾，立即释放服务与锁，再由任务自身执行取消清理。
        chatService.cancelBackgroundGenerations(activeGenerations.generationIds)
        stopService()
    }

    private fun stopService() {
        activeGenerations.clear()
        releaseWakeLock()
        if (isForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        stopSelf()
    }

    private fun ensureWakeLock() {
        if (wakeLockJob?.isActive == true) return
        wakeLockJob = serviceScope.launch {
            while (isActive && !activeGenerations.isEmpty) {
                // 每次均有系统级超时；最后一个任务结束、关闭开关、超时或服务销毁时主动释放。
                runCatching { wakeLock.acquire(WAKELOCK_TIMEOUT_MS) }
                    .onFailure { Log.w(TAG, "获取聊天唤醒锁失败", it) }
                delay(WAKELOCK_RENEW_MS)
            }
        }
    }

    private fun releaseWakeLock() {
        wakeLockJob?.cancel()
        wakeLockJob = null
        if (wakeLock.isHeld) wakeLock.release()
    }

    private fun startForegroundCompat(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
            isForeground = true
            true
        } catch (e: Exception) {
            // 部分 OEM ROM 会在系统侧拒绝 FGS 类型权限，抛 SecurityException
            Log.e(TAG, "Failed to start foreground service", e)
            false
        }
    }

    private fun buildLaunchPendingIntent(): PendingIntent {
        val conversationId = activeGenerations.latestConversationId
        val intent = Intent(this, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("conversationId", conversationId)
        }
        return PendingIntent.getActivity(
            this,
            conversationId?.hashCode() ?: 0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID)
        .setSmallIcon(R.drawable.small_icon)
        .setContentTitle(getString(R.string.notification_chat_generation_foreground))
        .setContentIntent(buildLaunchPendingIntent())
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .build()
}
