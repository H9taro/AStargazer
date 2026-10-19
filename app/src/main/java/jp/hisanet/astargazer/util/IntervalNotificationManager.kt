package jp.hisanet.astargazer.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import jp.hisanet.astargazer.MainActivity
import jp.hisanet.astargazer.R
import jp.hisanet.astargazer.model.IntervalShootingState
import jp.hisanet.astargazer.service.IntervalShootingService

class IntervalNotificationManager(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "interval_shooting_channel"
        const val NOTIFICATION_ID = 1001
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "インターバル撮影通知",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "インターバル撮影の進行状態を表示します"
            setSound(null, null)
        }
        notificationManager.createNotificationChannel(channel)
    }

    fun buildNotification(state: IntervalShootingState): Notification {
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            context,
            1,
            Intent(context, IntervalShootingService::class.java).apply {
                action = IntervalShootingService.ACTION_STOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("インターバル撮影中 (AStargazer)")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .addAction(
                android.R.drawable.ic_media_pause,
                "撮影停止",
                stopIntent
            )

        when (state) {
            is IntervalShootingState.Preparing -> {
                builder.setContentText("カメラ準備中...")
            }
            is IntervalShootingState.Shooting -> {
                val line1 = "撮影枚数: ${state.shotCount}枚 (残り約${state.remainingShots}枚)"
                val line2 = "ISO ${state.iso} / 露出 ${state.exposureSeconds}s"
                builder.setContentText(line1)
                    .setStyle(
                        NotificationCompat.BigTextStyle()
                            .bigText("$line1\n$line2\n経過時間: ${state.elapsedSeconds}秒")
                    )
            }
            is IntervalShootingState.Stopping -> {
                builder.setContentText("撮影停止処理中...")
            }
            is IntervalShootingState.Error -> {
                builder.setContentText("エラー: ${state.message}")
            }
            is IntervalShootingState.Idle -> {
                builder.setContentText("待機中")
            }
        }

        return builder.build()
    }

    fun updateNotification(state: IntervalShootingState) {
        notificationManager.notify(NOTIFICATION_ID, buildNotification(state))
    }
}
