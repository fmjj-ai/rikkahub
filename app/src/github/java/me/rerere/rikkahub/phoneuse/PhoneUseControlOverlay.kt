package me.rerere.rikkahub.phoneuse

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** 通知独立于截图时让出的控制条，停止操作不依赖聊天界面是否可见。 */
class PhoneUseStopReceiver : BroadcastReceiver(), KoinComponent {
    private val manager: PhoneUseManager by inject()
    override fun onReceive(context: Context, intent: Intent) { manager.stop() }
}

internal class PhoneUseControlOverlay(
    private val service: AccessibilityService,
    private val manager: PhoneUseManager,
) {
    private val windows = service.getSystemService(WindowManager::class.java)
    private val notifications = service.getSystemService(NotificationManager::class.java)
    private var panel: LinearLayout? = null
    private var label: TextView? = null
    private val channelId = "phone_use_control"
    private val notificationId = 2040

    init {
        notifications.createNotificationChannel(NotificationChannel(
            channelId, "手机操作控制", NotificationManager.IMPORTANCE_LOW,
        ))
    }

    fun update(action: String?, yielding: Boolean) {
        if (action == null) { dismiss(); return }
        val notificationAvailable = notifications.areNotificationsEnabled() &&
            notifications.getNotificationChannel(channelId)?.importance != NotificationManager.IMPORTANCE_NONE
        val description = when (action) {
            "observe" -> "读取屏幕"
            "screenshot" -> "截取屏幕"
            "tap" -> "点击"
            "long_press" -> "长按"
            "swipe" -> "滑动"
            "scroll" -> "滚动"
            "type" -> "输入文字"
            "press" -> "系统按键"
            "open_app" -> "打开应用"
            "list_apps" -> "列出应用"
            "wait" -> "等待界面"
            else -> action
        }
        if (panel == null) {
            val container = LinearLayout(service).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(12, 4, 8, 4)
                setBackgroundColor(Color.rgb(35, 37, 41))
            }
            label = TextView(service).apply { setTextColor(Color.WHITE); textSize = 13f }
            container.addView(label)
            container.addView(Button(service).apply {
                text = "返回聊天"
                setOnClickListener {
                    service.startActivity(Intent(service, RouteActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                }
            })
            container.addView(Button(service).apply {
                text = "停止"
                setOnClickListener { manager.stop() }
            })
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = 24 }
            try {
                windows.addView(container, params)
            } catch (e: Exception) {
                // 控制窗口无法显示时不继续盲目操作手机。
                android.util.Log.w("PhoneUseControl", "创建操作控制条失败", e)
                manager.stop()
                return
            }
            panel = container
        }
        label?.text = "Phone Use · $description"
        // 未获通知权限时保持控制条可见，优先保证用户能停止。
        panel?.visibility = if (yielding && notificationAvailable) View.INVISIBLE else View.VISIBLE
        val stop = PendingIntent.getBroadcast(
            service, notificationId, Intent(service, PhoneUseStopReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val launch = PendingIntent.getActivity(
            service, notificationId, Intent(service, RouteActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        if (notificationAvailable) {
            notifications.notify(notificationId, NotificationCompat.Builder(service, channelId)
                .setSmallIcon(R.drawable.small_icon)
                .setContentTitle("Phone Use · $description")
                .setContentText("点按停止可取消当前手机操作任务")
                .setContentIntent(launch)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(R.drawable.small_icon, "停止操作", stop)
                .build())
        }
    }

    fun dismiss() {
        panel?.let { runCatching { windows.removeViewImmediate(it) } }
        panel = null
        label = null
        notifications.cancel(notificationId)
    }
}
