package org.localsend.localsend_app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private const val REQUEST_CODE_PICK_DIRECTORY = 1
private const val REQUEST_CODE_PICK_DIRECTORY_PATH = 2
private const val REQUEST_CODE_PICK_FILE = 3
private const val REQUEST_CODE_LOCAL_NETWORK = 4

// Not available as a constant in compileSdk 36.
private const val PERMISSION_ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
private const val PERMISSION_NEARBY_WIFI_DEVICES = "android.permission.NEARBY_WIFI_DEVICES"
private const val API_LEVEL_ANDROID_13 = 33
private const val API_LEVEL_ANDROID_17 = 37
private const val SCREEN_ON_RETRY_DELAY_MS = 250L
private const val SCREEN_ON_MAX_RETRIES = 20

/**
 * Keeps the Wi-Fi multicast lock only while background receiving is enabled and the screen is
 * interactive. The HTTP server and its foreground service are intentionally unrelated to this
 * lock, so direct transfers from already-known devices continue while the screen is off.
 */
internal object LocalNetworkMulticastLock {
    @Suppress("DEPRECATION")
    private var lock: WifiManager.MulticastLock? = null
    private var requested = false
    private var receiverRegistered = false
    private var screenInteractiveCallback: (() -> Unit)? = null
    private var applicationContext: Context? = null
    private var screenOnRetries = 0
    private val screenOnHandler = Handler(Looper.getMainLooper())

    private val screenOnRunnable = object : Runnable {
        override fun run() {
            val context = applicationContext ?: return
            if (!requested) {
                return
            }

            update(context)
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (powerManager?.isInteractive == true) {
                screenOnRetries = 0
                screenInteractiveCallback?.invoke()
            } else if (screenOnRetries++ < SCREEN_ON_MAX_RETRIES) {
                screenOnHandler.postDelayed(this, SCREEN_ON_RETRY_DELAY_MS)
            } else {
                screenOnRetries = 0
            }
        }
    }

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> {
                    screenOnRetries = 0
                    screenOnHandler.removeCallbacks(screenOnRunnable)
                    screenOnHandler.post(screenOnRunnable)
                }
                Intent.ACTION_SCREEN_OFF -> {
                    screenOnRetries = 0
                    screenOnHandler.removeCallbacks(screenOnRunnable)
                    release()
                }
            }
        }
    }

    fun setScreenInteractiveCallback(callback: (() -> Unit)?) {
        screenInteractiveCallback = callback
    }

    @Suppress("DEPRECATION")
    fun set(context: Context, enabled: Boolean) {
        val applicationContext = context.applicationContext
        this.applicationContext = applicationContext
        requested = enabled
        if (enabled) {
            registerReceiver(applicationContext)
            update(applicationContext)
        } else {
            screenOnRetries = 0
            screenOnHandler.removeCallbacks(screenOnRunnable)
            unregisterReceiver(applicationContext)
            release()
        }
    }

    @Suppress("DEPRECATION")
    private fun registerReceiver(context: Context) {
        if (receiverRegistered) {
            return
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(screenStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(screenStateReceiver, filter)
        }
        receiverRegistered = true
    }

    @Suppress("DEPRECATION")
    private fun unregisterReceiver(context: Context) {
        if (!receiverRegistered) {
            return
        }
        context.unregisterReceiver(screenStateReceiver)
        receiverRegistered = false
    }

    @Suppress("DEPRECATION")
    private fun update(context: Context) {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (requested && powerManager?.isInteractive == true) {
            val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            if (lock == null) {
                lock = wifiManager.createMulticastLock("LocalSendMulticast").apply {
                    setReferenceCounted(false)
                }
            }
            if (lock?.isHeld != true) {
                lock?.acquire()
            }
        } else {
            release()
        }
    }

    @Suppress("DEPRECATION")
    private fun release() {
        if (lock?.isHeld == true) {
            lock?.release()
        }
    }
}

class MainActivity : FlutterActivity() {
    private var pendingResult: MethodChannel.Result? = null
    private var pendingPermissionResult: MethodChannel.Result? = null

    /// share_handler drops share intents arriving via onNewIntent while the Dart side
    /// is not subscribed to its media stream yet, which happens when this singleTask
    /// activity is relaunched into an existing task while the app is still starting.
    /// Hold such intents back until Dart reports readiness ("shareIntentReady"), then
    /// replay them through the regular plugin path.
    private val pendingShareIntents = mutableListOf<Intent>()
    private val shareIntentReady: Boolean get() = platform?.shareIntentReady == true

    override fun onNewIntent(intent: Intent) {
        if (!shareIntentReady && (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE)) {
            pendingShareIntents.add(intent)
            return
        }
        super.onNewIntent(intent)
    }

    private fun onShareIntentReady() {
        platform?.shareIntentReady = true
        val pending = pendingShareIntents.toList()
        pendingShareIntents.clear()
        for (intent in pending) {
            super.onNewIntent(intent)
        }
    }

    private var platform: ReceivePlatform? = null

    // Keep the root isolate and its receive controller alive together with the server.
    // Reopening the Activity must not spawn another set of networking isolates.
    override fun provideFlutterEngine(context: Context): FlutterEngine? {
        platform = retainedPlatform
        return platform?.engine
    }

    override fun shouldDestroyEngineWithHost(): Boolean = platform?.backgroundReceive != true

    companion object {
        private var retainedPlatform: ReceivePlatform? = null

        internal fun retainReceiver(platform: ReceivePlatform, enabled: Boolean) {
            retainedPlatform = if (enabled) platform else null
        }

        // Mirror FlutterActivity builders so notifications open this Activity subclass.
        fun withNewEngine(): NewEngineIntentBuilder {
            return NewEngineIntentBuilder(MainActivity::class.java)
        }

        fun createDefaultIntent(launchContext: Context): Intent {
            return withNewEngine().build(launchContext)
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        if (platform == null) platform = ReceivePlatform(applicationContext, flutterEngine)
        platform!!.activityHandler = { call, result ->
            when (call.method) {
                "pickDirectory" -> {
                    pendingResult = result
                    openDirectoryPicker(onlyPath = false)
                }

                "pickFiles" -> {
                    pendingResult = result
                    openFilePicker()
                }

                "pickDirectoryPath" -> {
                    pendingResult = result
                    openDirectoryPicker(onlyPath = true)
                }

                "openContentUri" -> {
                    openUri(context, call.argument<String>("uri")!!)
                    result.success(null)
                }

                "openGallery" -> {
                    openGallery()
                    result.success(null)
                }

                "shareIntentReady" -> {
                    onShareIntentReady()
                    result.success(null)
                }

                "requestLocalNetworkPermission" -> {
                    if (hasLocalNetworkPermission()) {
                        result.success(true)
                    } else {
                        pendingPermissionResult = result
                        requestPermissions(arrayOf(localNetworkPermission()!!), REQUEST_CODE_LOCAL_NETWORK)
                    }
                }

                else -> result.error("ACTIVITY_REQUIRED", "Open LocalSend to perform this action", null)
            }
        }
    }

    override fun cleanUpFlutterEngine(flutterEngine: FlutterEngine) {
        platform?.activityHandler = null
        pendingResult?.error("ACTIVITY_DESTROYED", "Activity was destroyed", null)
        pendingResult = null
        pendingPermissionResult?.success(false)
        pendingPermissionResult = null
        if (platform?.backgroundReceive != true) platform?.dispose()
        super.cleanUpFlutterEngine(flutterEngine)
    }

    /// Android 17+ uses ACCESS_LOCAL_NETWORK. Android 13-16 use the nearby-devices
    /// permission for the same Wi-Fi/local-network access. Older versions grant it
    /// implicitly.
    private fun localNetworkPermission(): String? {
        return when {
            Build.VERSION.SDK_INT >= API_LEVEL_ANDROID_17 -> PERMISSION_ACCESS_LOCAL_NETWORK
            Build.VERSION.SDK_INT >= API_LEVEL_ANDROID_13 -> PERMISSION_NEARBY_WIFI_DEVICES
            else -> null
        }
    }

    private fun hasLocalNetworkPermission(): Boolean {
        val permission = localNetworkPermission()
        if (permission == null) {
            return true
        }
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_LOCAL_NETWORK) {
            pendingPermissionResult?.success(hasLocalNetworkPermission())
            pendingPermissionResult = null
        }
    }

    private fun openDirectoryPicker(onlyPath: Boolean) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        startActivityForResult(
            intent,
            if (onlyPath) REQUEST_CODE_PICK_DIRECTORY_PATH else REQUEST_CODE_PICK_DIRECTORY
        )
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            putExtra("multi-pick", true)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_CODE_PICK_FILE)
    }

    @SuppressLint("WrongConstant")
    @Override
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == Activity.RESULT_CANCELED) {
            pendingResult?.error("CANCELED", "Canceled", null)
            pendingResult = null
            return
        }

        if (resultCode != Activity.RESULT_OK || data == null) {
            pendingResult?.error("Error $resultCode", "Failed to access directory or file", null)
            pendingResult = null
            return
        }

        when (requestCode) {
            REQUEST_CODE_PICK_DIRECTORY -> {
                val uri: Uri? = data.data
                val takeFlags: Int =
                    data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                if (uri != null) {
                    contentResolver.takePersistableUriPermission(uri, takeFlags)

                    val files = mutableListOf<FileInfo>()
                    listFiles(uri, files)
                    val resultData = PickDirectoryResult(uri.toString(), files)
                    pendingResult?.success(resultData.toMap())
                    pendingResult = null
                } else {
                    pendingResult?.error("Error", "Failed to access directory", null)
                    pendingResult = null
                }
            }

            REQUEST_CODE_PICK_DIRECTORY_PATH -> {
                val uri: Uri? = data.data
                val takeFlags: Int =
                    data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                if (uri != null) {
                    contentResolver.takePersistableUriPermission(uri, takeFlags)
                    pendingResult?.success(uri.toString())
                    pendingResult = null
                } else {
                    pendingResult?.error("Error", "Failed to access directory", null)
                    pendingResult = null
                }
            }

            REQUEST_CODE_PICK_FILE -> {
                val uriList: List<Uri> = when {
                    data.clipData != null -> {
                        val clipData = data.clipData
                        val uris = mutableListOf<Uri>()
                        for (i in 0 until clipData!!.itemCount) {
                            uris.add(clipData.getItemAt(i).uri)
                        }
                        uris
                    }

                    data.data != null -> listOf(data.data!!)
                    else -> {
                        pendingResult?.error("Error", "Failed to access file", null)
                        return
                    }
                }

                val takeFlags: Int =
                    data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

                val resultList = mutableListOf<FileInfo>()
                for (uri in uriList) {
                    contentResolver.takePersistableUriPermission(uri, takeFlags)
                    val documentFile = FastDocumentFile.fromDocumentUri(this, uri)
                    if (documentFile == null) {
                        pendingResult?.error("Error", "Failed to access file", null)
                        return
                    }
                    resultList.add(
                        FileInfo(
                            name = documentFile.name,
                            size = documentFile.size,
                            uri = uri.toString(),
                            lastModified = documentFile.lastModified?.toRfc3339(),
                        )
                    )
                }

                pendingResult?.success(resultList.map { it.toMap() })
                pendingResult = null
            }
        }
    }

    private fun listFiles(uri: Uri, files: MutableList<FileInfo>) {
        val pickedDir: FastDocumentFile = FastDocumentFile.fromTreeUri(this, uri)

        for (file in pickedDir.listFiles()) {
            if (file.isDirectory) {
                // Recursive call
                listFiles(file.uri, files)
            } else if (file.isFile) {
                files.add(
                    FileInfo(
                        name = file.name,
                        size = file.size,
                        uri = file.uri.toString(),
                        lastModified = file.lastModified?.toRfc3339(),
                    ),
                )
            }
        }
    }

    private fun openGallery() {
        val intent = Intent()
        intent.action = Intent.ACTION_VIEW
        intent.type = "image/*"
        startActivity(intent)
    }
}

data class PickDirectoryResult(
    val directoryUri: String,
    val files: List<FileInfo>,
) {
    fun toMap(): Map<String, Any> {
        return mapOf(
            "directoryUri" to directoryUri,
            "files" to files.map { it.toMap() }
        )
    }
}

data class FileInfo(
    val name: String,
    val size: Long,
    val uri: String,
    val lastModified: String?
) {
    fun toMap(): Map<String, Any?> {
        return mapOf(
            "name" to name,
            "size" to size,
            "uri" to uri,
            "lastModified" to lastModified
        )
    }
}

/// Formats milliseconds since epoch as an RFC 3339 string in UTC.
private fun Long.toRfc3339(): String {
    val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    format.timeZone = TimeZone.getTimeZone("UTC")
    return format.format(Date(this))
}
