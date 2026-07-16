package com.skip.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.skip.app.R

object NotificationHelper {

    const val CHANNEL_SERVICE = "ad_skip_service"
    const val CHANNEL_REMINDER = "ad_skip_reminder"
    const val NOTIFICATION_ID_SERVICE = 1001
    const val NOTIFICATION_ID_REMINDER = 1002

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // 服务通知渠道
        val serviceChannel = NotificationChannel(
            CHANNEL_SERVICE,
            "羊小跳服务",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "羊小跳运行状态"
            setShowBadge(false)
        }
        manager.createNotificationChannel(serviceChannel)

        // 提醒通知渠道
        val reminderChannel = NotificationChannel(
            CHANNEL_REMINDER,
            "服务提醒",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "开机后提醒开启无障碍服务"
        }
        manager.createNotificationChannel(reminderChannel)
    }

    fun buildServiceNotification(context: Context, skipCount: Int): android.app.Notification {
        val pendingIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setContentTitle("羊小跳正在运行")
            .setContentText("已为您跳过 $skipCount 次广告")
            .setSmallIcon(R.drawable.ic_skip_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun sendBootReminder(context: Context) {
        createChannels(context)

        val pendingIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDER)
            .setContentTitle("羊小跳提醒")
            .setContentText("无障碍服务未开启，点击此处前往设置")
            .setSmallIcon(R.drawable.ic_notification_info)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID_REMINDER, notification)
    }

    fun updateServiceNotification(context: Context, skipCount: Int) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID_SERVICE, buildServiceNotification(context, skipCount))
    }
}
