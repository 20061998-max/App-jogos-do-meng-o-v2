package com.telacast.mirror.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.telacast.mirror.R
import com.telacast.mirror.ui.MainActivity
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class ScreenMirrorService : Service() {

    companion object {
        const val ACTION_START = "com.telacast.mirror.ACTION_START"
        const val ACTION_STOP = "com.telacast.mirror.ACTION_STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val HTTP_PORT = 8080
        const val APP_CLIENT_HEADER = "X-TelaCast-App"
        const val APP_CLIENT_SIGNATURE = "Android-Client/1.0"
        const val PWA_CLIENT_SIGNATURE = "TelaCast-PWA-App/1.0"
        const val WEBOS_CLIENT_SIGNATURE = "TelaCast-LG-webOS/1.0"
        const val TV_CLIENT_SIGNATURE = "TelaCast-TV-App/1.0"
        const val DEVICE_TYPE_HEADER = "X-Device-Type"

        @Volatile
        var onlyAllowTvReceivers = true

        private const val MAX_TV_CLIENTS = 2
        private const val NOTIFICATION_CHANNEL_ID = "mengao_mirror_channel"
        private const val NOTIFICATION_ID = 1042
        private const val JPEG_QUALITY = 70
        private const val TARGET_FRAME_INTERVAL_MS = 50L // ~20 FPS
        private const val BOUNDARY = "frame"

        @Volatile
        var isRunning = false
            private set

        @Volatile
        var sessionToken: String = ""
            private set
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null
    private val serverActive = AtomicBoolean(false)
    private val streamClients = CopyOnWriteArrayList<BufferedOutputStream>()
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var lastFrameSentAt = 0L

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            encerrarTudo()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                encerrarTudo()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                if (resultCode != Activity.RESULT_OK || resultData == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }

                sessionToken = "TC-${UUID.randomUUID().toString().take(6).uppercase()}"
                iniciarForegroundService()
                iniciarCapturaEHttp(resultCode, resultData)
            }
        }
        return START_NOT_STICKY
    }

    private fun iniciarForegroundService() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Transmissão Jogos do Mengão",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }

        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Jogos do Mengão ativo (Somente TVs)")
            .setContentText("Token da sessão: $sessionToken · Porta $HTTP_PORT")
            .setSmallIcon(R.drawable.ic_launcher_mengao)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (wakeLock == null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "JogosDoMengao::LiveBroadcast")
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(6 * 60 * 60 * 1000L)
            }
        }
        isRunning = true
    }

    private fun iniciarCapturaEHttp(resultCode: Int, resultData: Intent) {
        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)?.also { mp ->
            mp.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
        }

        val (width, height, density) = calcularResolucaoCaptura()

        captureThread = HandlerThread("MengaoCapture").apply { start() }
        captureHandler = Handler(captureThread!!.looper)

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2).apply {
            setOnImageAvailableListener({ reader ->
                val agora = System.currentTimeMillis()
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    if (agora - lastFrameSentAt < TARGET_FRAME_INTERVAL_MS || streamClients.isEmpty()) {
                        return@setOnImageAvailableListener
                    }
                    lastFrameSentAt = agora

                    val plane = image.planes[0]
                    val buffer: ByteBuffer = plane.buffer
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride
                    val rowPadding = rowStride - pixelStride * width

                    val bitmap = Bitmap.createBitmap(
                        width + rowPadding / pixelStride,
                        height,
                        Bitmap.Config.ARGB_8888
                    )
                    bitmap.copyPixelsFromBuffer(buffer)

                    val cropped = if (rowPadding > 0) {
                        Bitmap.createBitmap(bitmap, 0, 0, width, height).also { bitmap.recycle() }
                    } else {
                        bitmap
                    }

                    val baos = ByteArrayOutputStream()
                    cropped.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos)
                    cropped.recycle()

                    transmitirFrameParaClientes(baos.toByteArray())
                } finally {
                    image.close()
                }
            }, captureHandler)
        }

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "JogosDoMengaoVirtualDisplay",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            captureHandler
        )

        iniciarServidorHttp()
    }

    private fun calcularResolucaoCaptura(): Triple<Int, Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)

        val scale = if (metrics.widthPixels > 720) 720f / metrics.widthPixels else 1f
        val w = (metrics.widthPixels * scale).toInt().coerceAtLeast(360)
        val h = (metrics.heightPixels * scale).toInt().coerceAtLeast(640)
        return Triple(w, h, metrics.densityDpi)
    }

    private fun iniciarServidorHttp() {
        serverActive.set(true)
        serverThread = Thread {
            runCatching {
                serverSocket = ServerSocket(HTTP_PORT)
                while (serverActive.get()) {
                    val socket = serverSocket?.accept() ?: break
                    Thread { atenderClienteHttp(socket) }.start()
                }
            }
        }.apply { start() }
    }

    private fun atenderClienteHttp(socket: Socket) {
        runCatching {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0) ?: "GET"
            val pathAndQuery = parts.getOrNull(1) ?: "/"

            val headersMap = mutableMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    headersMap[line.substring(0, colon).trim().lowercase()] =
                        line.substring(colon + 1).trim()
                }
            }

            val out = BufferedOutputStream(socket.getOutputStream())
            if (method.equals("OPTIONS", ignoreCase = true)) {
                val corsHeaders = buildString {
                    append("HTTP/1.1 204 No Content\r\n")
                    append("Access-Control-Allow-Origin: *\r\n")
                    append("Access-Control-Allow-Methods: GET, OPTIONS\r\n")
                    append("Access-Control-Allow-Headers: $APP_CLIENT_HEADER, $DEVICE_TYPE_HEADER\r\n")
                    append("Connection: close\r\n\r\n")
                }
                out.write(corsHeaders.toByteArray(Charsets.US_ASCII))
                out.flush()
                socket.close()
                return
            }

            val clientSignature = headersMap[APP_CLIENT_HEADER.lowercase()]
            val deviceType = headersMap[DEVICE_TYPE_HEADER.lowercase()]
            val hasProxyRelayHeader =
                headersMap.containsKey("x-forwarded-for") || headersMap.containsKey("via")
            val queryToken = pathAndQuery.substringAfter("token=", "").substringBefore("&")

            // Proteção anti-retransmissão: bloqueia proxies e restringe retransmissão a Smart TVs
            val isTvClient =
                (clientSignature == WEBOS_CLIENT_SIGNATURE || clientSignature == TV_CLIENT_SIGNATURE) &&
                    deviceType == "tv"
            val isAllowedClient = if (onlyAllowTvReceivers) {
                isTvClient
            } else {
                clientSignature == APP_CLIENT_SIGNATURE ||
                    clientSignature == PWA_CLIENT_SIGNATURE ||
                    isTvClient
            }

            val isAuthorizedAppStream =
                pathAndQuery.startsWith("/stream") &&
                    !hasProxyRelayHeader &&
                    streamClients.size < MAX_TV_CLIENTS &&
                    isAllowedClient &&
                    queryToken.isNotBlank() &&
                    queryToken == sessionToken

            if (isAuthorizedAppStream) {
                val headers = buildString {
                    append("HTTP/1.1 200 OK\r\n")
                    append("Access-Control-Allow-Origin: *\r\n")
                    append("Cache-Control: no-store, no-cache, private\r\n")
                    append("Pragma: no-cache\r\n")
                    append("Connection: close\r\n")
                    append("Content-Type: multipart/x-mixed-replace; boundary=--$BOUNDARY\r\n\r\n")
                }
                out.write(headers.toByteArray(Charsets.US_ASCII))
                out.flush()
                streamClients.add(out)
            } else {
                val html = gerarHtmlBloqueioNavegador().toByteArray(Charsets.UTF_8)
                val headers = buildString {
                    append("HTTP/1.1 403 Forbidden\r\n")
                    append("Access-Control-Allow-Origin: *\r\n")
                    append("Content-Type: text/html; charset=utf-8\r\n")
                    append("Content-Length: ${html.size}\r\n")
                    append("Connection: close\r\n\r\n")
                }
                out.write(headers.toByteArray(Charsets.US_ASCII))
                out.write(html)
                out.flush()
                socket.close()
            }
        }.onFailure {
            runCatching { socket.close() }
        }
    }

    private fun transmitirFrameParaClientes(jpegBytes: ByteArray) {
        val header = ("--$BOUNDARY\r\n" +
            "Content-Type: image/jpeg\r\n" +
            "Content-Length: ${jpegBytes.size}\r\n\r\n").toByteArray(Charsets.US_ASCII)
        val footer = "\r\n".toByteArray(Charsets.US_ASCII)

        for (client in streamClients) {
            try {
                client.write(header)
                client.write(jpegBytes)
                client.write(footer)
                client.flush()
            } catch (_: Exception) {
                streamClients.remove(client)
                runCatching { client.close() }
            }
        }
    }

    private fun gerarHtmlBloqueioNavegador(): String = """
        <!doctype html>
        <html lang="pt-BR">
        <head>
          <meta charset="utf-8" />
          <meta name="viewport" content="width=device-width, initial-scale=1.0" />
          <title>TelaCast — Acesso exclusivo pelo App</title>
          <style>
            body {
              margin: 0; min-height: 100vh; display: flex; align-items: center;
              justify-content: center; background: #0f172a; color: #f8fafc;
              font-family: system-ui, -apple-system, sans-serif; padding: 24px;
            }
            .card {
              max-width: 420px; background: #1e293b; border: 1px solid #334155;
              border-radius: 14px; padding: 28px; text-align: center;
            }
            h1 { font-size: 20px; margin: 0 0 10px; }
            p { font-size: 14px; color: #cbd5e1; line-height: 1.5; margin: 0; }
          </style>
        </head>
        <body>
          <div class="card">
            <h1>Visualização bloqueada no navegador</h1>
            <p>Esta transmissão pode ser assistida exclusivamente pelo aplicativo TelaCast com o token da sessão.</p>
          </div>
        </body>
        </html>
    """.trimIndent()

    private fun encerrarTudo() {
        isRunning = false
        sessionToken = ""
        serverActive.set(false)

        for (client in streamClients) {
            runCatching { client.close() }
        }
        streamClients.clear()

        runCatching { serverSocket?.close() }
        serverSocket = null

        virtualDisplay?.release()
        virtualDisplay = null

        imageReader?.close()
        imageReader = null

        mediaProjection?.unregisterCallback(projectionCallback)
        mediaProjection?.stop()
        mediaProjection = null

        captureThread?.quitSafely()
        captureThread = null

        runCatching {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        }
        wakeLock = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        encerrarTudo()
        super.onDestroy()
    }
}
