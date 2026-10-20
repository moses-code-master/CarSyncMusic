package app.carsync.music

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Active-session foreground notifications for microphone and location access.
 * Services are started from a visible user action, never silently from the background.
 */
object ConvoyForeground {
    const val LOCATION = 7351
    const val MICROPHONE = 7352
    fun startLocation(context: Context): Boolean = start(context, ConvoyLocationService::class.java)
    fun startMicrophone(context: Context): Boolean = start(context, ConvoyMicrophoneService::class.java)
    private fun start(context: Context, service: Class<out Service>): Boolean =
        try { ContextCompat.startForegroundService(context, Intent(context, service)); true }
        catch (_: Exception) { false }
    fun stopLocation(context: Context) { context.stopService(Intent(context, ConvoyLocationService::class.java)) }
    fun stopMicrophone(context: Context) { context.stopService(Intent(context, ConvoyMicrophoneService::class.java)) }
    fun notification(service: Service, channel: String, title: String, detail: String): Notification {
        val manager = service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
            NotificationChannel(channel, title, NotificationManager.IMPORTANCE_LOW)
        )
        val intent = PendingIntent.getActivity(
            service, 0, Intent(service, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(service, channel) else Notification.Builder(service)
        return builder.setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title).setContentText(detail).setContentIntent(intent)
            .setOngoing(true).build()
    }
}

class ConvoyLocationService : Service() {
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(ConvoyForeground.LOCATION,
            ConvoyForeground.notification(this, "carsync-location", "CarSync location active",
                "Sharing location with this convoy session"))
        return START_NOT_STICKY
    }
}

class ConvoyMicrophoneService : Service() {
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(ConvoyForeground.MICROPHONE,
            ConvoyForeground.notification(this, "carsync-microphone", "CarSync microphone active",
                "Push-to-talk channel open"))
        return START_NOT_STICKY
    }
}
