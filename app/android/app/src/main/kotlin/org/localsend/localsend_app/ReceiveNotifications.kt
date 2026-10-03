package org.localsend.localsend_app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.util.concurrent.Executors

/** Receive notifications are independent of the persistent foreground service. */
object ReceiveNotifications {
    private const val REQUEST_CHANNEL = "localsend_receive_requests_v1"
    private const val PROGRESS_CHANNEL = "localsend_receive_progress_v1"
    private const val RESULT_CHANNEL = "localsend_receive_results_v1"
    private const val REQUEST_ID = 10
    private const val PROGRESS_ID = 11
    private const val RESULT_ID = 12
    private val previews = Executors.newSingleThreadExecutor()
    var channel: MethodChannel? = null

    private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java)

    fun configure(context: Context, names: Map<String, String>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            listOf(
                Triple(REQUEST_CHANNEL, names.getValue("requests"), NotificationManager.IMPORTANCE_HIGH),
                Triple(PROGRESS_CHANNEL, names.getValue("progress"), NotificationManager.IMPORTANCE_HIGH),
                Triple(RESULT_CHANNEL, names.getValue("results"), NotificationManager.IMPORTANCE_DEFAULT),
            ).forEach { (id, name, importance) ->
                manager(context).createNotificationChannel(NotificationChannel(id, name, importance))
            }
        }
        // Pending sessions cannot survive process death. Completed notifications can.
        manager(context).activeNotifications.filter { it.id == REQUEST_ID || it.id == PROGRESS_ID }
            .forEach { manager(context).cancel(it.tag, it.id) }
    }

    private fun builder(context: Context, channel: String, title: String, text: String): Notification.Builder {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, channel)
        } else {
            Notification.Builder(context)
        }
        return builder.setSmallIcon(R.drawable.ic_receive_notification)
            .setContentTitle(title).setContentText(text)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentIntent(PendingIntent.getActivity(context, 0,
                Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
    }

    private fun action(context: Context, session: String, action: String, text: String? = null): PendingIntent {
        val intent = Intent(context, ReceiveNotificationReceiver::class.java).apply {
            this.action = action
            data = Uri.Builder().scheme("localsend").authority("notification").appendPath(session).appendPath(action).build()
            putExtra("sessionId", session)
            text?.let { putExtra("text", it) }
        }
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun request(context: Context, session: String, title: String, text: String, accept: String, ignore: String): Boolean {
        if (!manager(context).areNotificationsEnabled() ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager(context).getNotificationChannel(REQUEST_CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE)) {
            return false
        }
        manager(context).notify(session, REQUEST_ID, builder(context, REQUEST_CHANNEL, title, text)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setPriority(Notification.PRIORITY_HIGH).setDefaults(Notification.DEFAULT_ALL)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .addAction(Notification.Action.Builder(null, accept, action(context, session, "accept")).build())
            .addAction(Notification.Action.Builder(null, ignore, action(context, session, "ignore")).build())
            .setDeleteIntent(action(context, session, "ignore"))
            .build())
        return true
    }

    fun progress(context: Context, session: String, title: String, text: String, percent: Int, first: Boolean) {
        manager(context).cancel(session, REQUEST_ID)
        manager(context).notify(session, PROGRESS_ID, builder(context, PROGRESS_CHANNEL, title, text)
            .setCategory(Notification.CATEGORY_PROGRESS).setOngoing(true)
            .setPriority(Notification.PRIORITY_HIGH).setDefaults(Notification.DEFAULT_ALL)
            .setOnlyAlertOnce(!first).setProgress(100, percent.coerceIn(0, 100), false).build())
    }

    fun cancel(context: Context, session: String) {
        manager(context).cancel(session, REQUEST_ID)
        manager(context).cancel(session, PROGRESS_ID)
    }

    fun complete(context: Context, key: String, title: String, text: String, path: String?, type: String,
                 message: String?, open: String, copy: String) {
        val notification = builder(context, RESULT_CHANNEL, title, text)
            .setAutoCancel(true).setCategory(Notification.CATEGORY_STATUS)
            .setStyle(Notification.BigTextStyle().bigText(message ?: text))
        if (message != null) {
            // Keep large messages out of PendingIntent Binder payloads, and allow copying after process death.
            val directory = File(context.filesDir, "notification_messages").apply { mkdirs() }
            val file = File.createTempFile("message-", ".txt", directory).apply { writeText(message) }
            notification.addAction(shareAction(context, key,
                Intent(context, ShareReceivedActivity::class.java).putExtra("messageFile", file.name)))
            val copyIntent = action(context, key, "copy", file.name)
            notification.addAction(Notification.Action.Builder(null, copy, copyIntent).build())
                .setDeleteIntent(action(context, key, "delete", file.name))
        } else if (path != null) {
            try {
                val uri = if (path.startsWith("content://")) Uri.parse(path) else
                    FileProvider.getUriForFile(context, "${context.packageName}.received_files", File(path))
                val mime = context.contentResolver.getType(uri) ?: when (type) {
                    "image" -> "image/*"
                    "video" -> "video/*"
                    else -> "*/*"
                }
                notification.addAction(shareAction(context, key,
                    Intent(context, ShareReceivedActivity::class.java)
                        .putExtra(Intent.EXTRA_STREAM, uri).putExtra("mime", mime)))
                val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val pending = PendingIntent.getActivity(context, key.hashCode(), intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                notification.setContentIntent(pending)
                    .addAction(Notification.Action.Builder(null, open, pending).build())
            } catch (e: Exception) {
                Log.w("ReceiveNotifications", "Cannot create file open action", e)
            }
        }
        manager(context).notify(key, RESULT_ID, notification.build())
        if (path != null && (type == "image" || type == "video")) {
            previews.execute {
                try {
                    val bitmap = thumbnail(context, path, type == "video") ?: return@execute
                    notification.setLargeIcon(bitmap).setStyle(Notification.BigPictureStyle().bigPicture(bitmap))
                        .setOnlyAlertOnce(true)
                    // Do not resurrect a notification that the user already dismissed.
                    if (manager(context).activeNotifications.any { it.id == RESULT_ID && it.tag == key }) {
                        manager(context).notify(key, RESULT_ID, notification.build())
                    }
                } catch (e: Exception) {
                    Log.w("ReceiveNotifications", "Cannot load preview", e)
                }
            }
        }
    }

    private fun shareAction(context: Context, key: String, intent: Intent): Notification.Action {
        intent.data = Uri.Builder().scheme("localsend").authority("share").appendPath(key).build()
        val pending = PendingIntent.getActivity(context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Action.Builder(null, context.getString(R.string.share_received), pending).build()
    }

    private fun thumbnail(context: Context, path: String, video: Boolean): Bitmap? {
        val uri = if (path.startsWith("content://")) Uri.parse(path) else Uri.fromFile(File(path))
        if (video) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    return retriever.getScaledFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 512, 512)
                }
                val frame = retriever.frameAtTime ?: return null
                val ratio = 512.0 / maxOf(frame.width, frame.height).coerceAtLeast(512)
                return Bitmap.createScaledBitmap(frame, (frame.width * ratio).toInt(), (frame.height * ratio).toInt(), true)
            } finally {
                retriever.release()
            }
        }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        options.inSampleSize = 1
        while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 1024) options.inSampleSize *= 2
        options.inJustDecodeBounds = false
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }
}

class ReceiveNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val session = intent.getStringExtra("sessionId") ?: return
        when (intent.action) {
            "copy", "delete" -> {
                val name = intent.getStringExtra("text") ?: return
                if (name != File(name).name) return
                val file = File(File(context.filesDir, "notification_messages"), name)
                if (intent.action == "copy" && file.isFile) {
                    context.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("LocalSend", file.readText()))
                }
                file.delete()
                context.getSystemService(NotificationManager::class.java).cancel(session, 12)
            }
            "accept", "ignore" -> {
                val channel = ReceiveNotifications.channel
                if (channel == null) {
                    ReceiveNotifications.cancel(context, session)
                    return
                }
                channel.invokeMethod("receiveNotificationAction", mapOf("sessionId" to session, "action" to intent.action))
            }
        }
    }
}
