package org.localsend.localsend_app

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import java.io.File

/** A direct notification Activity launch also works when the Flutter engine has stopped. */
class ShareReceivedActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val share = Intent(Intent.ACTION_SEND)
            val messageFile = intent.getStringExtra("messageFile")
            if (messageFile != null) {
                if (messageFile != File(messageFile).name) return
                val file = File(File(filesDir, "notification_messages"), messageFile)
                if (!file.isFile) return
                share.type = "text/plain"
                share.putExtra(Intent.EXTRA_TEXT, file.readText())
            } else {
                @Suppress("DEPRECATION")
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: return
                share.type = intent.getStringExtra("mime") ?: "application/octet-stream"
                share.putExtra(Intent.EXTRA_STREAM, uri)
                share.clipData = ClipData.newRawUri("LocalSend", uri)
                share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, null))
        } catch (e: Exception) {
            Log.w("ShareReceivedActivity", "Cannot share received content", e)
        } finally {
            finish()
        }
    }
}
