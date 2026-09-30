package org.localsend.localsend_app

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.media.MediaScannerConnection
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.util.concurrent.Executors

/** Returns the newly inserted URI so received media can be opened from its notification. */
object GallerySaver {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun save(context: Context, path: String, image: Boolean, result: MethodChannel.Result) {
        worker.execute {
            val resolver = context.contentResolver
            var uri: android.net.Uri? = null
            try {
                val file = File(path)
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    val directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
                    directory.mkdirs()
                    var destination = File(directory, file.name)
                    var index = 1
                    while (destination.exists()) {
                        destination = File(directory, "${file.nameWithoutExtension} (${index++}).${file.extension}")
                    }
                    file.copyTo(destination)
                    MediaScannerConnection.scanFile(context, arrayOf(destination.path), null) { _, scannedUri ->
                        main.post { result.success(scannedUri?.toString() ?: destination.path) }
                    }
                    return@execute
                }
                val collection = if (image) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                    put(MediaStore.MediaColumns.MIME_TYPE,
                        MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: if (image) "image/jpeg" else "video/mp4")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                uri = resolver.insert(collection, values) ?: error("Could not create gallery entry")
                resolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } }
                    ?: error("Could not open gallery entry")
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                val savedUri = uri.toString()
                main.post { result.success(savedUri) }
            } catch (e: Exception) {
                uri?.let { resolver.delete(it, null, null) }
                main.post { result.error("gallery_save_failed", e.message, null) }
            }
        }
    }
}
