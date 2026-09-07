package com.localgpt.app.localai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.localgpt.app.MainActivity
import com.localgpt.app.R
import com.localgpt.app.data.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Foreground Service for downloading On-Device AI models in the background.
 * Keeps the download and process alive even when app is backgrounded or device is locked,
 * updating ongoing notifications with real-time speed, bytes, and percentage.
 * (Ported from KZKT.)
 */
class ModelDownloadService : Service() {
    companion object {
        const val ACTION_START = "com.localgpt.app.action.START_MODEL_DOWNLOAD"
        const val ACTION_CANCEL = "com.localgpt.app.action.CANCEL_MODEL_DOWNLOAD"

        const val CHANNEL_ID = "litechat_model_downloads"
        const val NOTIFICATION_ID = 2002

        private const val EXTRA_ID = "extra_id"
        private const val EXTRA_NAME = "extra_name"
        private const val EXTRA_FILE_NAME = "extra_file_name"
        private const val EXTRA_URL = "extra_url"
        private const val EXTRA_SIZE_BYTES = "extra_size_bytes"

        @Volatile
        private var channelCreated = false

        fun ensureNotificationChannel(context: Context) {
            if (channelCreated) return
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val channel =
                        NotificationChannel(
                            CHANNEL_ID,
                            "LiteChat Model Downloads",
                            NotificationManager.IMPORTANCE_LOW,
                        ).apply {
                            description = "Shows offline AI model download progress"
                            setShowBadge(false)
                        }
                    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    manager?.createNotificationChannel(channel)
                }
                channelCreated = true
            } catch (e: Exception) {
                Log.e("ModelDownloadService", "Failed to create notification channel", e)
            }
        }

        fun start(
            context: Context,
            id: String,
            name: String,
            fileName: String,
            downloadUrl: String,
            sizeBytes: Long,
        ) {
            try {
                ensureNotificationChannel(context)
                val intent =
                    Intent(context, ModelDownloadService::class.java).apply {
                        action = ACTION_START
                        putExtra(EXTRA_ID, id)
                        putExtra(EXTRA_NAME, name)
                        putExtra(EXTRA_FILE_NAME, fileName)
                        putExtra(EXTRA_URL, downloadUrl)
                        putExtra(EXTRA_SIZE_BYTES, sizeBytes)
                    }
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.e("ModelDownloadService", "Failed to start download service", e)
                LocalModelDownloader.getInstance(context).onServiceError(
                    id = id,
                    errorMessage = "Failed to start download service: ${e.localizedMessage ?: e.javaClass.simpleName}",
                    fileName = fileName,
                )
            }
        }

        fun cancel(
            context: Context,
            id: String,
        ) {
            try {
                val intent =
                    Intent(context, ModelDownloadService::class.java).apply {
                        action = ACTION_CANCEL
                        putExtra(EXTRA_ID, id)
                    }
                context.startService(intent)
            } catch (e: Exception) {
                Log.e("ModelDownloadService", "Failed to send cancel intent", e)
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .connectionPool(okhttp3.ConnectionPool(8, 5, TimeUnit.MINUTES))
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

    @Volatile
    private var isCancelled = false

    @Volatile
    private var currentCall: Call? = null

    private lateinit var modelManager: LocalModelManager
    private lateinit var settingsRepo: SettingsRepository

    override fun onCreate() {
        super.onCreate()
        modelManager = LocalModelManager(this)
        settingsRepo = SettingsRepository(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                isCancelled = true
                currentCall?.cancel()
                val id = intent.getStringExtra(EXTRA_ID) ?: ""
                LocalModelDownloader.getInstance(this).onServiceCancelled(id)
                stopForegroundNotification()
                stopSelf()
            }
            ACTION_START -> {
                val id =
                    intent.getStringExtra(EXTRA_ID) ?: run {
                        stopSelf()
                        return START_NOT_STICKY
                    }
                val name = intent.getStringExtra(EXTRA_NAME) ?: id
                val fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: "$id.litertlm"
                val url =
                    intent.getStringExtra(EXTRA_URL) ?: run {
                        stopSelf()
                        return START_NOT_STICKY
                    }
                val sizeBytes = intent.getLongExtra(EXTRA_SIZE_BYTES, 0L)

                ensureNotificationChannel(this)
                val initialNotification = buildNotification(name, 0f, 0L, sizeBytes, 0L)
                try {
                    ServiceCompat.startForeground(
                        this,
                        NOTIFICATION_ID,
                        initialNotification,
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                        } else {
                            0
                        },
                    )
                } catch (e: Exception) {
                    Log.e("ModelDownloadService", "startForeground failed", e)
                }

                scope.launch {
                    runDownload(id, name, fileName, url, sizeBytes)
                }
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private suspend fun runDownload(
        id: String,
        name: String,
        fileName: String,
        url: String,
        expectedSizeBytes: Long,
    ) {
        val downloader = LocalModelDownloader.getInstance(this)
        val modelsDir = modelManager.modelsDir
        modelsDir.mkdirs()

        val cleanFileName = File(fileName).name.ifBlank { "custom_model.litertlm" }
        val targetFile = File(modelsDir, cleanFileName)
        val tempFile = File(modelsDir, "$cleanFileName.tmp")

        val existingBytes = if (tempFile.exists()) tempFile.length() else 0L
        downloader.onServiceDownloading(
            id,
            if (expectedSizeBytes > 0) (existingBytes.toFloat() / expectedSizeBytes.toFloat()).coerceIn(0f, 1f) else 0f,
            existingBytes,
            expectedSizeBytes,
            0L,
            cleanFileName,
            name,
        )

        try {
            val hfToken =
                settingsRepo.settingsFlow
                    .first()
                    .huggingFaceToken
                    .trim()
            val response = executeRequestWithAuthAndRedirects(url, hfToken, existingBytes)

            if (!response.isSuccessful) {
                val errorMsg =
                    when (response.code) {
                        400 -> "HTTP 400: Request format or token was rejected by server."
                        401 -> "HTTP 401: Model requires a Hugging Face Token (add token in Settings tab)."
                        403 -> "HTTP 403: Access denied. If this is a gated model, please accept its license on Hugging Face and provide an access token."
                        404 -> "HTTP 404: Model file was not found in repository."
                        416 -> "HTTP 416: Requested Range Not Satisfiable."
                        500, 502, 503 -> "HTTP ${response.code}: Server is busy."
                        else -> "HTTP ${response.code}: ${response.message}"
                    }
                response.close()
                if (response.code in listOf(400, 401, 403, 404, 416)) {
                    if (tempFile.exists()) tempFile.delete()
                }
                throw IllegalStateException(errorMsg)
            }

            val isPartial = response.code == 206
            val body = response.body ?: throw IllegalStateException("Empty response body")
            val contentLen = body.contentLength()

            val downloadedStart = if (isPartial) existingBytes else 0L
            val totalBytes =
                when {
                    isPartial && contentLen > 0 -> existingBytes + contentLen
                    contentLen > 0 -> contentLen
                    expectedSizeBytes > 0 -> expectedSizeBytes
                    else -> 1_000_000_000L
                }

            var downloaded = downloadedStart
            var lastUpdate = System.currentTimeMillis()
            var bytesSinceLastUpdate = 0L
            var currentSpeed = 0L

            java.io.BufferedInputStream(body.byteStream(), 256 * 1024).use { input ->
                java.io.BufferedOutputStream(FileOutputStream(tempFile, isPartial), 256 * 1024).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        if (isCancelled) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        bytesSinceLastUpdate += read

                        val now = System.currentTimeMillis()
                        val elapsed = now - lastUpdate
                        if (elapsed >= 500) {
                            val instantSpeed = (bytesSinceLastUpdate * 1000L) / maxOf(1L, elapsed)
                            currentSpeed =
                                if (currentSpeed == 0L) {
                                    instantSpeed
                                } else {
                                    ((currentSpeed * 0.70f) + (instantSpeed * 0.30f)).toLong()
                                }
                            lastUpdate = now
                            bytesSinceLastUpdate = 0L

                            val progress = (downloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                            downloader.onServiceDownloading(id, progress, downloaded, totalBytes, currentSpeed, cleanFileName, name)
                            updateNotification(name, progress, downloaded, totalBytes, currentSpeed)
                        }
                    }
                    output.flush()
                }
            }

            if (isCancelled) {
                if (tempFile.exists()) tempFile.delete()
                downloader.onServiceCancelled(id)
            } else {
                if (targetFile.exists()) targetFile.delete()
                val renameSuccess = tempFile.renameTo(targetFile)
                if (renameSuccess || (targetFile.exists() && targetFile.length() > 0)) {
                    downloader.onServiceCompleted(id)
                    showCompletionNotification(name)
                } else {
                    try {
                        tempFile.copyTo(targetFile, overwrite = true)
                        tempFile.delete()
                        downloader.onServiceCompleted(id)
                        showCompletionNotification(name)
                    } catch (e: Exception) {
                        Log.e("ModelDownloadService", "Failed to copy temp file to target", e)
                        throw IllegalStateException("Failed to save downloaded model file: ${e.localizedMessage}")
                    }
                }
            }
        } catch (e: CancellationException) {
            if (tempFile.exists()) tempFile.delete()
            downloader.onServiceCancelled(id)
        } catch (e: Exception) {
            Log.e("ModelDownloadService", "Download error for $id", e)
            val msg = e.localizedMessage ?: "Download failed"
            downloader.onServiceError(id, msg, cleanFileName)
            showErrorNotification(name, msg)
        } finally {
            stopForegroundNotification()
            stopSelf()
        }
    }

    private fun executeRequestWithAuthAndRedirects(
        initialUrl: String,
        hfToken: String,
        rangeStart: Long = 0L,
    ): Response {
        var currentUrl = initialUrl
        var attempts = 0
        while (attempts < 8) {
            attempts++
            val httpUrl = currentUrl.toHttpUrlOrNull()
                ?: throw IllegalArgumentException("Invalid URL: $currentUrl")
            val host = httpUrl.host
            val isHfHost = host.equals("huggingface.co", ignoreCase = true) ||
                host.equals("hf.co", ignoreCase = true) ||
                host.equals("api.huggingface.co", ignoreCase = true)

            val queryNames = httpUrl.queryParameterNames
            val isSignedUrl = queryNames.any { name ->
                name.contains("Signature", ignoreCase = true) ||
                    name.contains("Policy", ignoreCase = true) ||
                    name.contains("X-Amz", ignoreCase = true) ||
                    name.contains("Key-Pair-Id", ignoreCase = true)
            }
            val isCdn = host.contains("cdn") || host.contains("amazonaws") || host.contains("cloudfront") || isSignedUrl

            val reqBuilder = Request.Builder().url(httpUrl)
            if (isHfHost && !isCdn && hfToken.isNotBlank()) {
                reqBuilder.header("Authorization", "Bearer $hfToken")
            }
            if (rangeStart > 0) {
                reqBuilder.header("Range", "bytes=$rangeStart-")
            }
            reqBuilder.header("Accept-Encoding", "identity")
            reqBuilder.header("User-Agent", "LiteChat-App/0.1.0 (Android; LiteRT-Downloader)")

            val call = client.newCall(reqBuilder.build())
            currentCall = call
            val response = call.execute()
            if (response.isRedirect) {
                val location = response.header("Location")
                response.close()
                if (location.isNullOrBlank()) {
                    throw IllegalStateException("Server redirected without a Location header")
                }
                currentUrl = httpUrl.resolve(location)?.toString() ?: location
                continue
            }
            return response
        }
        throw IllegalStateException("Too many redirects from server")
    }

    private fun buildNotification(
        name: String,
        progress: Float,
        downloadedBytes: Long,
        totalBytes: Long,
        speedBps: Long,
    ): Notification {
        val pct = (progress * 100).toInt().coerceIn(0, 100)
        val doneMb = downloadedBytes.toFloat() / (1024 * 1024)
        val totalMb = maxOf(1L, totalBytes).toFloat() / (1024 * 1024)
        val speedMb = speedBps.toFloat() / (1024 * 1024)

        val contentText = String.format(Locale.ROOT, "%.1f / %.1f MB · %.1f MB/s", doneMb, totalMb, speedMb)

        val openIntent = Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP }
        val openPendingIntent =
            PendingIntent.getActivity(
                this,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val cancelIntent =
            Intent(this, ModelDownloadService::class.java).apply {
                action = ACTION_CANCEL
            }
        val cancelPendingIntent =
            PendingIntent.getService(
                this,
                1,
                cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        return NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Downloading Model: $name")
            .setContentText(contentText)
            .setSubText("$pct%")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, pct, false)
            .setContentIntent(openPendingIntent)
            .addAction(R.mipmap.ic_launcher, "Cancel", cancelPendingIntent)
            .build()
    }

    private fun updateNotification(
        name: String,
        progress: Float,
        downloadedBytes: Long,
        totalBytes: Long,
        speedBps: Long,
    ) {
        try {
            if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return
            val notif = buildNotification(name, progress, downloadedBytes, totalBytes, speedBps)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.notify(NOTIFICATION_ID, notif)
        } catch (e: Exception) {
            Log.e("ModelDownloadService", "Failed to update notification", e)
        }
    }

    private fun showCompletionNotification(name: String) {
        try {
            if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return
            val openIntent = Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP }
            val openPendingIntent =
                PendingIntent.getActivity(
                    this,
                    0,
                    openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )

            val notif =
                NotificationCompat
                    .Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle("Model Download Complete")
                    .setContentText("Model $name is ready for offline use.")
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setAutoCancel(true)
                    .setContentIntent(openPendingIntent)
                    .build()

            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.notify(NOTIFICATION_ID + 1, notif)
        } catch (e: Exception) {
            Log.e("ModelDownloadService", "Failed to show completion notification", e)
        }
    }

    private fun showErrorNotification(
        name: String,
        errorMsg: String,
    ) {
        try {
            if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return
            val notif =
                NotificationCompat
                    .Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle("Failed to Download $name")
                    .setContentText(errorMsg)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setAutoCancel(true)
                    .build()

            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.notify(NOTIFICATION_ID + 2, notif)
        } catch (e: Exception) {
            Log.e("ModelDownloadService", "Failed to show error notification", e)
        }
    }

    private fun stopForegroundNotification() {
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            Log.e("ModelDownloadService", "stopForeground error", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
