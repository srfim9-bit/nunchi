package app.nunchi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * 상시 케어 서비스. 운전 중 화면이 막히거나 다른 앱을 띄워도 눈치의 판단·개입이 멈추지 않게 한다.
 */
class CareService : Service() {

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        (application as NunchiApp).runtime.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "NUNCHI 케어", NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_nunchi)
            .setContentTitle("NUNCHI가 실내를 살피는 중")
            .setContentText("운전 중엔 조용히, 필요할 때만 한마디 합니다")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private companion object {
        const val CHANNEL_ID = "nunchi_care"
        const val NOTIFICATION_ID = 1
    }
}
