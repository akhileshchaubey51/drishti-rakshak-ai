package com.homesentinel.owner.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.homesentinel.owner.R
import com.homesentinel.owner.ui.AlertLogActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

object NotificationHelper {

    const val CHANNEL_ID = "home_sentinel_critical_alerts"
    private const val CHANNEL_NAME = "HomeSentinel Critical Intruder Alerts"

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_ALARM)
                .build()

            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Urgent intruder and unknown person detection alerts with snapshot preview"
                enableLights(true)
                lightColor = android.graphics.Color.RED
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 800)
                setSound(soundUri, audioAttributes)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * Shows a Heads-Up system notification immediately on the Owner's device
     * with high-importance sound, vibration, intruder snapshot, and tap-to-open AlertLog.
     */
    fun showIntruderNotification(
        context: Context,
        title: String,
        body: String,
        imageUrl: String?
    ) {
        createNotificationChannel(context)

        CoroutineScope(Dispatchers.IO).launch {
            var bitmap: Bitmap? = null
            if (!imageUrl.isNullOrEmpty() && imageUrl.startsWith("http")) {
                try {
                    val stream = URL(imageUrl).openStream()
                    bitmap = BitmapFactory.decodeStream(stream)
                } catch (e: Exception) {
                    // Ignore image load failure
                }
            }

            withContext(Dispatchers.Main) {
                val intent = Intent(context, AlertLogActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }

                val pendingIntent = PendingIntent.getActivity(
                    context,
                    System.currentTimeMillis().toInt(),
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

                val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_siren)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setAutoCancel(true)
                    .setSound(soundUri)
                    .setVibrate(longArrayOf(0, 500, 200, 500, 200, 800))
                    .setContentIntent(pendingIntent)
                    .setColor(ContextCompat.getColor(context, R.color.alert_red))

                if (bitmap != null) {
                    builder.setLargeIcon(bitmap)
                    builder.setStyle(
                        NotificationCompat.BigPictureStyle()
                            .bigPicture(bitmap)
                            .setSummaryText(body)
                    )
                } else {
                    builder.setStyle(NotificationCompat.BigTextStyle().bigText(body))
                }

                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(System.currentTimeMillis().toInt(), builder.build())
            }
        }
    }
}
