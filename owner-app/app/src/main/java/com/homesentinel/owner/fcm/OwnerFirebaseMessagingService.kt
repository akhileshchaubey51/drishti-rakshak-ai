package com.homesentinel.owner.fcm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.homesentinel.owner.R
import com.homesentinel.owner.data.AlertRecord
import com.homesentinel.owner.data.OwnerDatabase
import com.homesentinel.owner.ui.LiveStreamActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.URL

class OwnerFirebaseMessagingService : FirebaseMessagingService() {

    private val tag = "OwnerFCM"
    private val channelId = "home_sentinel_critical_alerts"

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(tag, "Owner FCM registration token: $token")
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        val title = remoteMessage.data["title"] ?: remoteMessage.notification?.title ?: "🚨 SECURITY ALERT!"
        val body = remoteMessage.data["body"] ?: remoteMessage.notification?.body ?: "Unknown person detected by HomeSentinel radar!"
        val imageUrl = remoteMessage.data["imageUrl"]

        saveAlertLocally(title, body, imageUrl)
        showHeadsUpNotification(title, body, imageUrl)
    }

    private fun saveAlertLocally(title: String, body: String, imageUrl: String?) {
        CoroutineScope(Dispatchers.IO).launch {
            val db = OwnerDatabase.getInstance(applicationContext)
            val record = AlertRecord(
                alertType = "UNKNOWN_PERSON",
                label = body,
                confidence = 0.95f,
                isAuthorized = false,
                sirenTriggered = true,
                snapshotPath = imageUrl,
                timestamp = System.currentTimeMillis()
            )
            db.alertRecordDao().insert(record)
        }
    }

    private fun showHeadsUpNotification(title: String, body: String, imageUrl: String?) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Critical Security Intrusion Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "High priority security alerts with alarm and live stream link"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 800)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(this, LiveStreamActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_siren)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setSound(soundUri)
            .setVibrate(longArrayOf(0, 500, 200, 500, 200, 800))
            .setContentIntent(pendingIntent)

        if (!imageUrl.isNullOrEmpty()) {
            try {
                val stream = URL(imageUrl).openStream()
                val bitmap = BitmapFactory.decodeStream(stream)
                builder.setLargeIcon(bitmap)
                builder.setStyle(NotificationCompat.BigPictureStyle().bigPicture(bitmap).setSummaryText(body))
            } catch (e: Exception) {
                builder.setStyle(NotificationCompat.BigTextStyle().bigText(body))
            }
        } else {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(body))
        }

        notificationManager.notify(System.currentTimeMillis().toInt(), builder.build())
    }
}
