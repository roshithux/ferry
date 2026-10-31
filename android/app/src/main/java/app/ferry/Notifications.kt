package app.ferry

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat

object Notifications {
    const val SERVICE_ID = 1
    private const val CHANNEL_SERVICE = "service"
    private const val CHANNEL_FILES = "files"

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, "Ready to receive", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Keeps Ferry reachable from your Mac."
                setShowBadge(false)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_FILES, "Received files", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    fun service(context: Context, macName: String?, online: Boolean): Notification {
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val sendClipboard = PendingIntent.getActivity(
            context, 1,
            Intent(context, ClipboardActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val text = when {
            macName == null -> "Not paired yet. Tap to pair with your Mac."
            online -> "Connected to $macName"
            else -> "Waiting for $macName on this Wi-Fi"
        }
        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_ferry)
            .setContentTitle("Ferry")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .apply { if (macName != null) addAction(R.drawable.ic_clipboard_send, "Send clipboard", sendClipboard) }
            .build()
    }

    fun fileReceived(context: Context, uri: Uri, name: String, macName: String) {
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, context.contentResolver.getType(uri))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val notification = NotificationCompat.Builder(context, CHANNEL_FILES)
            .setSmallIcon(R.drawable.ic_ferry)
            .setContentTitle("Received from $macName")
            .setContentText(name)
            .setContentIntent(PendingIntent.getActivity(context, uri.hashCode(), view, PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(uri.hashCode(), notification) }
    }
}
