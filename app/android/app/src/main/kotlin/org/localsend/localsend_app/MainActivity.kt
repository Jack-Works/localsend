package org.localsend.localsend_app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.DocumentsContract
import android.provider.Settings
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone


private const val CHANNEL = "org.localsend.localsend_app/localsend"
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
private object LocalNetworkMulticastLock {
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
    private var shareIntentReady = false

    override fun onNewIntent(intent: Intent) {
        if (!shareIntentReady && (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE)) {
            pendingShareIntents.add(intent)
            return
        }
        super.onNewIntent(intent)
    }

    private fun onShareIntentReady() {
        shareIntentReady = true
        val pending = pendingShareIntents.toList()
        pendingShareIntents.clear()
        for (intent in pending) {
            super.onNewIntent(intent)
        }
    }

    // Overriding the static methods we need from the Java class, as described
    // in the documentation of `FlutterActivity.NewEngineIntentBuilder`
    companion object {
        fun withNewEngine(): NewEngineIntentBuilder {
            return NewEngineIntentBuilder(MainActivity::class.java)
        }

        fun createDefaultIntent(launchContext: Context): Intent {
            return withNewEngine().build(launchContext)
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        val methodChannel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            CHANNEL
        )
        ReceiveNotifications.channel = methodChannel
        LocalNetworkMulticastLock.setScreenInteractiveCallback {
            methodChannel.invokeMethod("screenInteractive", null)
        }
        methodChannel.setMethodCallHandler { call, result ->
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

                "createDirectory" -> handleCreateDirectory(call, result)

                "getFileDescriptor" -> handleGetFileDescriptor(call, result)

                "createFile" -> handleCreateFile(call, result)

                "openFileForWriting" -> handleOpenFileForWriting(call, result)

                "openContentUri" -> {
                    openUri(context, call.argument<String>("uri")!!)
                    result.success(null)
                }

                "saveReceivedMedia" -> {
                    GallerySaver.save(applicationContext, call.argument<String>("path")!!, call.argument<Boolean>("image")!!, result)
                }

                "openGallery" -> {
                    openGallery()
                    result.success(null)
                }

                "shareIntentReady" -> {
                    onShareIntentReady()
                    result.success(null)
                }

                "isAnimationsEnabled" -> {
                    result.success(isAnimationsEnabled())
                }

                "getDownloadsDirectory" -> {
                    result.success(getDownloadsDirectory())
                }

                "requestLocalNetworkPermission" -> {
                    if (hasLocalNetworkPermission()) {
                        result.success(true)
                    } else {
                        pendingPermissionResult = result
                        requestPermissions(arrayOf(localNetworkPermission()!!), REQUEST_CODE_LOCAL_NETWORK)
                    }
                }

                "setLocalNetworkMulticastLock" -> {
                    setLocalNetworkMulticastLock(call.argument<Boolean>("enabled") == true)
                    result.success(null)
                }

                "configureReceiveNotifications" -> {
                    ReceiveNotifications.configure(applicationContext, mapOf(
                        "requests" to call.argument<String>("requests")!!,
                        "progress" to call.argument<String>("progress")!!,
                        "results" to call.argument<String>("results")!!))
                    result.success(null)
                }
                "showReceiveRequest" -> {
                    result.success(ReceiveNotifications.request(applicationContext,
                        call.argument<String>("sessionId")!!, call.argument<String>("title")!!,
                        call.argument<String>("text")!!, call.argument<String>("accept")!!, call.argument<String>("ignore")!!))
                }
                "showReceiveProgress" -> {
                    ReceiveNotifications.progress(applicationContext,
                        call.argument<String>("sessionId")!!, call.argument<String>("title")!!,
                        call.argument<String>("text")!!, call.argument<Int>("percent")!!, call.argument<Boolean>("first") == true)
                    result.success(null)
                }
                "cancelReceiveNotification" -> {
                    ReceiveNotifications.cancel(applicationContext, call.argument<String>("sessionId")!!)
                    result.success(null)
                }
                "showReceiveComplete" -> {
                    ReceiveNotifications.complete(applicationContext,
                        call.argument<String>("key")!!, call.argument<String>("title")!!, call.argument<String>("text")!!,
                        call.argument<String>("path"), call.argument<String>("type")!!, call.argument<String>("message"),
                        call.argument<String>("open")!!, call.argument<String>("copy")!!)
                    result.success(null)
                }

                else -> result.notImplemented()
            }
        }
    }

    override fun onDestroy() {
        LocalNetworkMulticastLock.setScreenInteractiveCallback(null)
        super.onDestroy()
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

    /// Android may stop delivering Wi-Fi multicast packets while the activity is in the
    /// background unless the process holds a MulticastLock. Keep it only while the screen is
    /// interactive to avoid holding the battery-intensive lock during screen-off idle time.
    private fun setLocalNetworkMulticastLock(enabled: Boolean) {
        LocalNetworkMulticastLock.set(applicationContext, enabled)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_LOCAL_NETWORK) {
            pendingPermissionResult?.success(hasLocalNetworkPermission())
            pendingPermissionResult = null
        }
    }

    /// Absolute path of the shared "Download" directory (usually /storage/emulated/0/Download).
    @Suppress("DEPRECATION")
    private fun getDownloadsDirectory(): String {
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
    }

    private fun isAnimationsEnabled() : Boolean {
        return Settings.Global.getFloat(this.getContentResolver(),
            Settings.Global.ANIMATOR_DURATION_SCALE, 1.0f) != 0.0f;
    }

    private fun handleGetFileDescriptor(call: MethodCall, result: MethodChannel.Result) {
        val uriString = call.argument<String>("uri")
        if (uriString == null) {
            result.error("INVALID_ARGUMENT", "Missing content URI", null)
            return
        }

        val uri = Uri.parse(uriString)
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
            result.error("INVALID_ARGUMENT", "Expected a content:// URI", null)
            return
        }

        try {
            val parcelFileDescriptor = contentResolver.openFileDescriptor(uri, "r")
            if (parcelFileDescriptor == null) {
                result.error("OPEN_FAILED", "The content provider did not return a file descriptor", null)
                return
            }

            // Ownership of the detached descriptor is transferred to the caller. It must be
            // closed by Rust (or whichever native consumer receives it) after use.
            parcelFileDescriptor.use {
                result.success(it.detachFd())
            }
        } catch (e: SecurityException) {
            result.error("PERMISSION_DENIED", e.message ?: "Permission denied for content URI", null)
        } catch (e: Exception) {
            result.error("OPEN_FAILED", e.message ?: "Failed to open content URI", null)
        }
    }

    /// Creates a new file inside a SAF directory and opens it for writing.
    ///
    /// Returns the URI of the created document (Android may rename the file on
    /// collisions) and an owned writable file descriptor. The descriptor must be
    /// closed by the native consumer it is passed to.
    private fun handleCreateFile(call: MethodCall, result: MethodChannel.Result) {
        val parentUriString = call.argument<String>("parentUri")
        val fileName = call.argument<String>("fileName")
        val mimeType = call.argument<String>("mimeType") ?: "application/octet-stream"
        if (parentUriString == null || fileName == null) {
            result.error("INVALID_ARGUMENT", "Missing parentUri or fileName", null)
            return
        }

        try {
            val parentUri = Uri.parse(parentUriString)

            // A pure tree URI (content://…/tree/X) must be converted to its
            // document form before it can be used as a parent document.
            val segments = parentUri.pathSegments
            val parentDocumentUri = if (segments.size == 2 && segments[0] == "tree") {
                DocumentsContract.buildDocumentUriUsingTree(
                    parentUri,
                    DocumentsContract.getTreeDocumentId(parentUri)
                )
            } else {
                parentUri
            }

            val documentUri =
                DocumentsContract.createDocument(contentResolver, parentDocumentUri, mimeType, fileName)
            if (documentUri == null) {
                result.error("CREATE_FAILED", "Could not create $fileName in $parentUriString", null)
                return
            }

            // "wt" is write + truncate: the document is new, unless the provider
            // handed out an existing one instead of creating a second document.
            val parcelFileDescriptor = contentResolver.openFileDescriptor(documentUri, "wt")
            if (parcelFileDescriptor == null) {
                result.error("OPEN_FAILED", "The content provider did not return a file descriptor", null)
                return
            }

            parcelFileDescriptor.use {
                result.success(
                    mapOf(
                        "uri" to documentUri.toString(),
                        "fd" to it.detachFd(),
                    )
                )
            }
        } catch (e: SecurityException) {
            result.error("PERMISSION_DENIED", e.message ?: "Permission denied for content URI", null)
        } catch (e: Exception) {
            result.error("CREATE_FAILED", e.message ?: "Failed to create file", null)
        }
    }

    /// Opens an existing document created by [handleCreateFile] for writing,
    /// discarding its current content.
    ///
    /// Used to write a file again after a failed attempt, so that it keeps its
    /// name instead of being created a second time under a numbered one.
    ///
    /// Returns an owned writable file descriptor. It stays open after this call
    /// and must be closed by the native consumer it is passed to.
    private fun handleOpenFileForWriting(call: MethodCall, result: MethodChannel.Result) {
        val uriString = call.argument<String>("uri")
        if (uriString == null) {
            result.error("INVALID_ARGUMENT", "Missing content URI", null)
            return
        }

        val uri = Uri.parse(uriString)
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
            result.error("INVALID_ARGUMENT", "Expected a content:// URI", null)
            return
        }

        try {
            // "wt" is write + truncate. A document provider may ignore the
            // truncation, so the writer additionally shortens the file itself.
            val parcelFileDescriptor = contentResolver.openFileDescriptor(uri, "wt")
            if (parcelFileDescriptor == null) {
                result.error("OPEN_FAILED", "The content provider did not return a file descriptor", null)
                return
            }

            parcelFileDescriptor.use {
                result.success(it.detachFd())
            }
        } catch (e: SecurityException) {
            result.error("PERMISSION_DENIED", e.message ?: "Permission denied for content URI", null)
        } catch (e: Exception) {
            result.error("OPEN_FAILED", e.message ?: "Failed to open content URI", null)
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

    @SuppressLint("WrongConstant")
    private fun handleCreateDirectory(call: MethodCall, result: MethodChannel.Result) {
        val documentUri = Uri.parse(call.argument<String>("documentUri")!!)
        val directoryName = call.argument<String>("directoryName")!!

        if (folderExists(documentUri, directoryName)) {
            result.success(null)
            return
        }

        DocumentsContract.createDocument(
            context.contentResolver, documentUri, DocumentsContract.Document.MIME_TYPE_DIR,
            directoryName
        )

        result.success(null)
    }

    private fun folderExists(documentUri: Uri, folderName: String): Boolean {
        var cursor: Cursor? = null
        try {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(documentUri, DocumentsContract.getDocumentId(documentUri))
            cursor = contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ),
                null,
                null,
                null,
            )

            if (cursor != null) {
                while (cursor.moveToNext()) {
                    val displayName = cursor.getString(0)
                    val mimeType = cursor.getString(1)

                    if (folderName == displayName && DocumentsContract.Document.MIME_TYPE_DIR == mimeType) {
                        return true
                    }
                }
            }
        } finally {
            cursor?.close()
        }
        return false
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
