package com.madadiaspora.chekeov2

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.TextUtils
import android.util.Base64
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import io.flutter.plugin.common.MethodChannel
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class OverlayService : Service() {

    companion object {
        private const val ACTION_STOP = "com.madadiaspora.chekeov2.STOP_OVERLAY"
        private const val CHANNEL_ID = "mcheck_overlay"
        private const val NOTIFICATION_ID = 4211
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"

        @Volatile
        var isRunning: Boolean = false
            private set

        var channel: MethodChannel? = null

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, OverlayService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private val colorPrimary = 0xFF18976B.toInt()
    private val colorPrimaryText = 0xFF0E6B4E.toInt()
    private val colorInk = 0xFF0D1B14.toInt()
    private val colorInkSoft = 0xFF24352C.toInt()
    private val colorMuted = 0xFF5D6E64.toInt()
    private val colorLime = 0xFFB2F093.toInt()
    private val colorLeaf = 0xFF57B43E.toInt()
    private val colorSurface = 0xFFFFFFFF.toInt()
    private val colorLine = 0xFFDDE3DB.toInt()
    private val colorFake = 0xFFB42318.toInt()
    private val colorReal = 0xFF0E6B4E.toInt()
    private val colorUncertain = 0xFF8A6A00.toInt()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var resultCard: View? = null
    private var mediaProjection: MediaProjection? = null
    private var scanning = false

    @Volatile
    private var analysisToken = 0

    @Volatile
    private var lastImages: List<ByteArray> = emptyList()
    private var lastMediaVideo = false
    private var loadingTicker: Runnable? = null
    private var lastOcrText = ""

    private val recognizerDelegate = lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
    private val recognizer: TextRecognizer by recognizerDelegate

    private val removeCardRunnable = Runnable { removeResultCard() }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            mediaProjection = null
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startInForeground()
        try {
            createBubble()
            isRunning = true
        } catch (e: Exception) {
            toast("Impossible d'afficher le bouton flottant")
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (mediaProjection == null) {
            val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                ?: Activity.RESULT_CANCELED
            @Suppress("DEPRECATION")
            val data = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
            if (resultCode != Activity.RESULT_OK || data == null) {
                toast("Capture d'écran non autorisée")
                stopSelf()
                return START_NOT_STICKY
            }
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = try {
                manager.getMediaProjection(resultCode, data)
            } catch (e: Exception) {
                null
            }
            if (projection == null) {
                toast("Impossible de démarrer la capture")
                stopSelf()
                return START_NOT_STICKY
            }
            projection.registerCallback(projectionCallback, mainHandler)
            mediaProjection = projection
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        mainHandler.post {
            removeResultCard()
            val params = bubbleParams ?: return@post
            clampPosition(params)
            bubbleView?.let { view ->
                try {
                    windowManager.updateViewLayout(view, params)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun startInForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "m-check scan",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Maintient le bouton de scan actif"
            }
            manager.createNotificationChannel(channel)
        }
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_stat_mcheck)
            .setContentTitle("m-check")
            .setContentText("Touchez le bouton flottant pour scanner l'écran")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Arrêter", stopIntent)
            .build()
    }

    private fun createBubble() {
        if (bubbleView != null) return
        val size = dp(52)
        val root = FrameLayout(this).apply {
            isClickable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = "Scanner l'écran"
            elevation = 0f
            background = GradientDrawable(
                GradientDrawable.Orientation.TR_BL,
                intArrayOf(colorLime, colorLeaf, colorPrimary)
            ).apply {
                shape = GradientDrawable.OVAL
            }
        }

        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_scan)
            setColorFilter(0xFFFFFFFF.toInt())
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        root.addView(icon, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))

        val metrics = screenMetrics()
        val params = WindowManager.LayoutParams(
            size,
            size,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = metrics.widthPixels - size - dp(10)
            y = metrics.heightPixels / 2
        }

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragged = false
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        val longPress = Runnable {
            if (!dragged) {
                root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                toast("Mode scan désactivé")
                stopSelf()
            }
        }

        root.setOnTouchListener { view, event ->
            val p = bubbleParams ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = p.x
                    startY = p.y
                    dragged = false
                    view.alpha = 0.82f
                    mainHandler.postDelayed(longPress, 700)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragged && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragged = true
                        mainHandler.removeCallbacks(longPress)
                    }
                    if (dragged) {
                        p.x = (startX + dx).toInt()
                        p.y = (startY + dy).toInt()
                        clampPosition(p)
                        windowManager.updateViewLayout(view, p)
                        view.alpha = 0.9f
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    mainHandler.removeCallbacks(longPress)
                    view.alpha = 1f
                    if (dragged) {
                        snapToEdge(p)
                        windowManager.updateViewLayout(view, p)
                    } else {
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        view.performClick()
                        startScan()
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPress)
                    view.alpha = 1f
                    if (dragged) {
                        snapToEdge(p)
                        windowManager.updateViewLayout(view, p)
                    }
                    true
                }

                else -> false
            }
        }
        root.setOnClickListener { }

        windowManager.addView(root, params)
        bubbleView = root
        bubbleParams = params
    }

    private fun startScan() {
        if (scanning) return
        val projection = mediaProjection
        if (projection == null) {
            toast("Capture non autorisée. Relancez le mode scan.")
            return
        }
        scanning = true
        analysisToken++
        removeResultCard()
        bubbleView?.contentDescription = "Analyse en cours"
        bubbleView?.visibility = View.GONE
        mainHandler.postDelayed({ captureSequence(projection) }, 260)
    }

    private fun captureSequence(projection: MediaProjection) {
        captureOnce(projection) { first ->
            if (first == null) {
                finishScan(null, emptyList(), false)
                return@captureOnce
            }
            mainHandler.postDelayed({
                captureOnce(projection) { second ->
                    if (second == null) {
                        runOcrOnFrames(listOf(first), false)
                        return@captureOnce
                    }
                    if (!hasMotion(first, second)) {
                        second.recycle()
                        runOcrOnFrames(listOf(first), false)
                        return@captureOnce
                    }
                    mainHandler.postDelayed({
                        captureOnce(projection) { third ->
                            val frames = mutableListOf(first, second)
                            if (third != null) frames.add(third)
                            runOcrOnFrames(frames, true)
                        }
                    }, 700)
                }
            }, 700)
        }
    }

    private fun captureOnce(projection: MediaProjection, onResult: (Bitmap?) -> Unit) {
        val metrics = screenMetrics()
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi
        var reader: ImageReader? = null
        var display: VirtualDisplay? = null
        var completed = false

        fun release() {
            try {
                display?.release()
            } catch (_: Exception) {
            }
            try {
                reader?.close()
            } catch (_: Exception) {
            }
            display = null
            reader = null
        }

        fun finish(result: Bitmap?) {
            if (completed) return
            completed = true
            release()
            onResult(result)
        }

        try {
            val imageReader = ImageReader.newInstance(
                width,
                height,
                PixelFormat.RGBA_8888,
                2
            )
            reader = imageReader
            imageReader.setOnImageAvailableListener({ current ->
                if (completed) {
                    try {
                        current.acquireLatestImage()?.close()
                    } catch (_: Exception) {
                    }
                    return@setOnImageAvailableListener
                }
                val image = try {
                    current.acquireLatestImage()
                } catch (_: Exception) {
                    null
                }
                if (image == null) {
                    return@setOnImageAvailableListener
                }
                val bitmap = try {
                    imageToBitmap(image)
                } finally {
                    image.close()
                }
                finish(bitmap)
            }, mainHandler)

            display = projection.createVirtualDisplay(
                "m-check-scan",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface,
                null,
                mainHandler
            )
            mainHandler.postDelayed({ finish(null) }, 6000)
        } catch (_: Exception) {
            finish(null)
        }
    }

    private fun hasMotion(first: Bitmap, second: Bitmap): Boolean {
        return try {
            val size = 64
            val smallFirst = Bitmap.createScaledBitmap(first, size, size, false)
            val smallSecond = Bitmap.createScaledBitmap(second, size, size, false)
            var total = 0L
            for (y in 0 until size) {
                for (x in 0 until size) {
                    val pa = smallFirst.getPixel(x, y)
                    val pb = smallSecond.getPixel(x, y)
                    total += abs(((pa shr 16) and 0xFF) - ((pb shr 16) and 0xFF))
                    total += abs(((pa shr 8) and 0xFF) - ((pb shr 8) and 0xFF))
                    total += abs((pa and 0xFF) - (pb and 0xFF))
                }
            }
            smallFirst.recycle()
            smallSecond.recycle()
            (total / (size * size * 3)) > 6
        } catch (_: Exception) {
            false
        }
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        if (rowPadding < 0) return null
        val paddedWidth = image.width + rowPadding / pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(buffer)
        if (paddedWidth == image.width) return padded
        val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        padded.recycle()
        return cropped
    }

    private fun runOcrOnFrames(frames: List<Bitmap>, isVideo: Boolean) {
        if (frames.isEmpty()) {
            finishScan(null, frames, isVideo)
            return
        }
        bubbleView?.visibility = View.VISIBLE
        bubbleView?.alpha = 0.6f
        recognizer.process(InputImage.fromBitmap(frames.first(), 0))
            .addOnSuccessListener { text -> finishScan(text.text, frames, isVideo) }
            .addOnFailureListener { finishScan(null, frames, isVideo) }
    }

    private fun finishScan(rawText: String?, frames: List<Bitmap>, isVideo: Boolean) {
        scanning = false
        bubbleView?.visibility = View.VISIBLE
        bubbleView?.alpha = 1f
        bubbleView?.contentDescription = "Scanner l'écran"
        val text = rawText?.trim().orEmpty()
        if (text.isNotEmpty()) {
            copyToClipboard(text)
        }
        lastOcrText = text
        if (frames.isEmpty()) {
            lastImages = emptyList()
            showErrorCard(
                title = "ANALYSE IMPOSSIBLE",
                message = "Impossible de capturer l'écran. Réessayez.",
                showRetry = false
            )
            return
        }
        if (SecureConfig.apiKey().isEmpty()) {
            frames.forEach { it.recycle() }
            lastImages = emptyList()
            showErrorCard(
                title = "ANALYSE INDISPONIBLE",
                message = "Aucune clé API DeepSeek dans cette build.",
                showRetry = false
            )
            return
        }
        startAnalysis(frames, text, isVideo)
    }

    private fun startAnalysis(frames: List<Bitmap>, text: String, isVideo: Boolean) {
        val token = ++analysisToken
        showLoadingCard(isVideo, frames.size, text.isNotEmpty())
        analysisExecutor.execute {
            val encoded = mutableListOf<ByteArray>()
            for (frame in frames) {
                val bytes = encodeImage(frame)
                frame.recycle()
                if (bytes != null) encoded.add(bytes)
            }
            if (token != analysisToken) return@execute
            if (encoded.isEmpty()) {
                mainHandler.post {
                    if (token == analysisToken) {
                        showErrorCard(
                            title = "ANALYSE IMPOSSIBLE",
                            message = "Impossible de préparer l'image.",
                            showRetry = false
                        )
                    }
                }
                return@execute
            }
            lastImages = encoded
            lastMediaVideo = isVideo
            runAnalysis(token, encoded, text, isVideo)
        }
    }

    private fun retryAnalysis() {
        val images = lastImages
        if (images.isEmpty()) {
            showErrorCard(
                title = "ANALYSE IMPOSSIBLE",
                message = "Refaites un scan pour relancer l'analyse.",
                showRetry = false
            )
            return
        }
        if (SecureConfig.apiKey().isEmpty()) {
            showErrorCard(
                title = "ANALYSE INDISPONIBLE",
                message = "Aucune clé API DeepSeek dans cette build.",
                showRetry = false
            )
            return
        }
        val token = ++analysisToken
        val isVideo = lastMediaVideo
        showLoadingCard(isVideo, images.size, lastOcrText.isNotEmpty())
        val text = lastOcrText
        analysisExecutor.execute {
            runAnalysis(token, images, text, isVideo)
        }
    }

    private fun runAnalysis(
        token: Int,
        images: List<ByteArray>,
        text: String,
        isVideo: Boolean
    ) {
        try {
            val apiKey = SecureConfig.apiKey()
            if (apiKey.isEmpty()) {
                mainHandler.post {
                    if (token == analysisToken) {
                        showErrorCard(
                            title = "ANALYSE INDISPONIBLE",
                            message = "Aucune clé API DeepSeek dans cette build.",
                            showRetry = false
                        )
                    }
                }
                return
            }
            val encoded = images.map { Base64.encodeToString(it, Base64.NO_WRAP) }
            val analysis = FakeNewsAnalyzer.analyze(apiKey, encoded, text, isVideo)
            mainHandler.post {
                if (token == analysisToken) {
                    showAnalysisCard(analysis)
                }
            }
        } catch (error: FakeNewsAnalyzer.AnalysisException) {
            mainHandler.post {
                if (token == analysisToken) {
                    showErrorCard(
                        title = "ANALYSE IMPOSSIBLE",
                        message = error.message ?: "Erreur inconnue.",
                        showRetry = true
                    )
                }
            }
        } catch (error: Exception) {
            mainHandler.post {
                if (token == analysisToken) {
                    showErrorCard(
                        title = "ANALYSE IMPOSSIBLE",
                        message = "Connexion impossible. Vérifiez votre réseau.",
                        showRetry = true
                    )
                }
            }
        }
    }

    private fun encodeImage(bitmap: Bitmap): ByteArray? {
        return try {
            val longest = max(bitmap.width, bitmap.height)
            val scaled = if (longest > 1280) {
                val ratio = 1280f / longest
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * ratio).toInt().coerceAtLeast(1),
                    (bitmap.height * ratio).toInt().coerceAtLeast(1),
                    true
                )
            } else {
                bitmap
            }
            val output = ByteArrayOutputStream()
            val compressed = scaled.compress(Bitmap.CompressFormat.JPEG, 90, output)
            if (scaled !== bitmap) scaled.recycle()
            if (compressed) output.toByteArray() else null
        } catch (_: Exception) {
            null
        }
    }

    private fun copyToClipboard(text: String) {
        try {
            val manager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            manager.setPrimaryClip(ClipData.newPlainText("m-check", text))
        } catch (_: Exception) {
        }
    }

    private fun showLoadingCard(isVideo: Boolean, frameCount: Int, webSearch: Boolean) {
        val card = newCard()
        addHeader(card)
        val body = addBody(card)
        body.addView(metaLabel("ANALYSE", 8))
        val status = paragraph("Analyse en cours…", 17f, colorInk, 8).apply {
            setTypeface(typeface, Typeface.BOLD)
        }
        body.addView(status)
        val detail = when {
            isVideo && webSearch -> "Vidéo · $frameCount images · vérification web · 15 à 35 s."
            isVideo -> "Vidéo · $frameCount images · généralement 10 à 25 s."
            webSearch -> "Image · vérification web · généralement 10 à 30 s."
            else -> "Image · généralement 5 à 15 s."
        }
        body.addView(paragraph(detail, 12f, colorMuted, 6))
        showCard(card, 0)
        val startedAt = System.currentTimeMillis()
        val ticker = object : Runnable {
            override fun run() {
                val seconds = ((System.currentTimeMillis() - startedAt) / 1000).toInt()
                status.text = "Analyse en cours… $seconds s"
                mainHandler.postDelayed(this, 1000)
            }
        }
        loadingTicker = ticker
        mainHandler.post(ticker)
    }

    private fun showAnalysisCard(analysis: FakeNewsAnalyzer.Analysis) {
        val card = newCard()
        addHeader(card)
        val body = addBody(card)

        val verdictRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        verdictRow.addView(verdictTag(analysis.verdict))
        verdictRow.addView(
            TextView(this).apply {
                text = "${analysis.confidence} % de confiance"
                setTextColor(colorMuted)
                textSize = 11.5f
                setPadding(dp(10), 0, 0, 0)
            }
        )
        body.addView(
            verdictRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        )

        if (analysis.summary.isNotEmpty()) {
            body.addView(
                paragraph(analysis.summary, 14f, colorInk, 10).apply {
                    maxLines = 3
                    ellipsize = TextUtils.TruncateAt.END
                }
            )
        }

        if (analysis.reasons.isNotEmpty()) {
            body.addView(metaLabel("POURQUOI", 12))
            analysis.reasons.forEach { addBullet(body, it) }
        }

        addDivider(body)
        if (analysis.textPresent) {
            addSectionRow(body, "TEXTE", analysis.textVerdict)
            if (analysis.textSource.isNotEmpty()) {
                body.addView(sourceLabel(analysis.textSource))
            }
            if (analysis.sources.isNotEmpty()) {
                body.addView(webSourcesLabel(analysis.sources))
            }
            if (analysis.claims.isEmpty()) {
                addBullet(body, "Aucune affirmation exploitable détectée.")
            } else {
                analysis.claims.forEach { claim -> addClaim(body, claim) }
            }
        } else {
            addSectionNote(body, "TEXTE", "AUCUN TEXTE DÉTECTÉ")
        }

        addDivider(body)
        val visualTitle = if (analysis.media == "video") "VIDÉO" else "IMAGE"
        addSectionRow(body, visualTitle, analysis.imageVerdict)
        body.addView(analysisFlags(analysis))
        if (analysis.imageSignals.isEmpty()) {
            addBullet(body, "Aucun indice visuel marquant.")
        } else {
            analysis.imageSignals.forEach { addBullet(body, it) }
        }

        addDivider(body)
        body.addView(
            paragraph(
                "Analyse IA — ne prouve pas la provenance d'une image.",
                10f,
                colorMuted,
                2
            )
        )
        showCard(card, 60000)
    }

    private fun showErrorCard(
        title: String,
        message: String,
        showRetry: Boolean
    ) {
        val card = newCard()
        addHeader(card)
        val body = addBody(card)
        body.addView(metaLabel("ANALYSE", 8))
        body.addView(
            paragraph(title, 16f, colorInk, 8).apply {
                setTypeface(typeface, Typeface.BOLD)
            }
        )
        body.addView(paragraph(message, 12.5f, colorMuted, 6))

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
        }
        if (showRetry) {
            actions.addView(textAction("RÉESSAYER") { retryAnalysis() })
        }
        if (actions.childCount > 0) {
            body.addView(
                actions,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4) }
            )
        }
        showCard(card, 30000)
    }

    private fun newCard(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(8), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(colorSurface)
                setStroke(dp(1), colorLine)
            }
            elevation = 0f
        }
    }

    private fun addHeader(card: LinearLayout) {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val dot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(1).toFloat()
                setColor(colorLime)
            }
        }
        header.addView(dot, LinearLayout.LayoutParams(dp(6), dp(6)))
        val title = TextView(this).apply {
            text = "m-check"
            setTextColor(colorInk)
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(8), 0, 0, 0)
        }
        header.addView(
            title,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        val close = ImageView(this).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(colorInk)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(12), dp(12), dp(12), dp(12))
            contentDescription = "Fermer le résultat"
            isClickable = true
            isFocusable = true
            setOnClickListener { closeCard() }
        }
        header.addView(close, LinearLayout.LayoutParams(dp(48), dp(48)))
        card.addView(header)
    }

    private fun addBody(card: LinearLayout): LinearLayout {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val chromeHeight = dp(12) + dp(48) + dp(16)
        val maxCardHeight = (screenMetrics().heightPixels * 0.60f).toInt()
        val scroll = MaxHeightScrollView(this).apply {
            maxHeight = (maxCardHeight - chromeHeight).coerceAtLeast(dp(160))
            isVerticalScrollBarEnabled = false
            addView(
                body,
                FrameLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        card.addView(scroll)
        return body
    }

    private fun metaLabel(text: String, top: Int = 0): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(colorMuted)
            textSize = 9.5f
            letterSpacing = 0.12f
            setTypeface(typeface, Typeface.BOLD)
            if (top > 0) setPadding(0, dp(top), 0, 0)
        }
    }

    private fun paragraph(text: String, size: Float, color: Int, top: Int = 0): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(color)
            textSize = size
            setLineSpacing(0f, 1.2f)
            if (top > 0) setPadding(0, dp(top), dp(8), 0) else setPadding(0, 0, dp(8), 0)
        }
    }

    private fun addBullet(body: LinearLayout, text: String) {
        body.addView(
            TextView(this).apply {
                this.text = "•  $text"
                setTextColor(colorInkSoft)
                textSize = 12.5f
                setLineSpacing(0f, 1.2f)
                setPadding(0, dp(8), dp(8), 0)
            }
        )
    }

    private fun addClaim(body: LinearLayout, claim: FakeNewsAnalyzer.Claim) {
        body.addView(
            TextView(this).apply {
                text = "•  ${claim.claim}"
                setTextColor(colorInk)
                textSize = 12.5f
                setLineSpacing(0f, 1.2f)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(10), dp(8), 0)
            }
        )
        if (claim.assessment.isNotEmpty()) {
            body.addView(
                TextView(this).apply {
                    text = claim.assessment
                    setTextColor(colorMuted)
                    textSize = 12f
                    setLineSpacing(0f, 1.2f)
                    setPadding(0, dp(2), dp(8), 0)
                }
            )
        }
    }

    private fun addDivider(body: LinearLayout) {
        body.addView(
            View(this).apply { setBackgroundColor(colorLine) },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)
            ).apply {
                topMargin = dp(14)
                bottomMargin = dp(2)
                rightMargin = dp(12)
            }
        )
    }

    private fun addSectionRow(body: LinearLayout, title: String, verdict: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(metaLabel(title))
        row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        row.addView(
            TextView(this).apply {
                text = verdictLabel(verdict)
                setTextColor(verdictColor(verdict))
                textSize = 9.5f
                letterSpacing = 0.1f
                setTypeface(typeface, Typeface.BOLD)
            }
        )
        body.addView(
            row,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(10)
                rightMargin = dp(12)
            }
        )
    }

    private fun addSectionNote(body: LinearLayout, title: String, note: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(metaLabel(title))
        row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        row.addView(
            TextView(this).apply {
                text = note
                setTextColor(colorMuted)
                textSize = 9.5f
                letterSpacing = 0.1f
                setTypeface(typeface, Typeface.BOLD)
            }
        )
        body.addView(
            row,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(10)
                rightMargin = dp(12)
            }
        )
    }

    private fun sourceLabel(source: String): TextView {
        return TextView(this).apply {
            text = "SOURCE · " + source.uppercase(Locale.FRENCH)
            setTextColor(colorPrimaryText)
            textSize = 9f
            letterSpacing = 0.08f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(8), dp(8), 0)
        }
    }

    private fun webSourcesLabel(sources: List<String>): TextView {
        return TextView(this).apply {
            text = "SOURCES · " + sources.joinToString(" · ").uppercase(Locale.FRENCH)
            setTextColor(colorMuted)
            textSize = 9f
            letterSpacing = 0.08f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(6), dp(8), 0)
        }
    }

    private fun analysisFlags(analysis: FakeNewsAnalyzer.Analysis): TextView {
        val parts = mutableListOf<String>()
        if (analysis.aiGenerated) parts.add("IA")
        if (analysis.manipulated) parts.add("MONTAGE")
        if (analysis.watermarkPresent) {
            val name = analysis.watermarkName.ifBlank { "FILIGRANE" }
            var label = name.uppercase(Locale.FRENCH)
            if (analysis.watermarkLocation.isNotBlank()) {
                label += " · " + analysis.watermarkLocation.uppercase(Locale.FRENCH)
            }
            parts.add(label)
        }
        val hasFlags = parts.isNotEmpty()
        if (!hasFlags) {
            parts.add("AUCUN INDICE IA NI MONTAGE")
        }
        return TextView(this).apply {
            text = parts.joinToString(" · ")
            setTextColor(
                when {
                    analysis.aiGenerated || analysis.manipulated -> colorFake
                    analysis.watermarkPresent -> colorPrimaryText
                    else -> colorMuted
                }
            )
            textSize = 9f
            letterSpacing = 0.08f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(8), dp(8), 0)
        }
    }

    private fun verdictTag(verdict: String): TextView {
        return TextView(this).apply {
            text = verdictLabel(verdict)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 11f
            letterSpacing = 0.08f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(9), dp(4), dp(9), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dp(4).toFloat()
                setColor(verdictColor(verdict))
            }
        }
    }

    private fun textAction(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(colorPrimaryText)
            textSize = 10f
            letterSpacing = 0.1f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(16), 0)
            minimumHeight = dp(48)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    private fun showCard(card: View, autoDismissMs: Long) {
        removeResultCard()
        setBubbleTouchable(false)
        val metrics = screenMetrics()
        val width = min(metrics.widthPixels - dp(32), dp(380))
        val params = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            val targetGap = (metrics.heightPixels * 0.10f).toInt()
            y = (targetGap - bottomNavigationInset()).coerceAtLeast(0)
        }
        try {
            windowManager.addView(card, params)
        } catch (_: Exception) {
            setBubbleTouchable(true)
            return
        }
        resultCard = card
        if (autoDismissMs > 0) {
            mainHandler.postDelayed(removeCardRunnable, autoDismissMs)
        }
    }

    private fun closeCard() {
        analysisToken++
        removeResultCard()
    }

    private fun removeResultCard() {
        mainHandler.removeCallbacks(removeCardRunnable)
        loadingTicker?.let { mainHandler.removeCallbacks(it) }
        loadingTicker = null
        val hadCard = resultCard != null
        resultCard?.let {
            try {
                windowManager.removeViewImmediate(it)
            } catch (_: Exception) {
            }
        }
        resultCard = null
        if (hadCard) setBubbleTouchable(true)
    }

    private fun setBubbleTouchable(touchable: Boolean) {
        val view = bubbleView ?: return
        val params = bubbleParams ?: return
        params.flags = if (touchable) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Exception) {
        }
    }

    private fun verdictColor(verdict: String): Int = when (verdict) {
        "fake" -> colorFake
        "real" -> colorReal
        else -> colorUncertain
    }

    private fun verdictLabel(verdict: String): String = when (verdict) {
        "fake" -> "FAKE"
        "real" -> "RÉEL"
        else -> "INCERTAIN"
    }

    private fun clampPosition(p: WindowManager.LayoutParams) {
        val metrics = screenMetrics()
        val size = dp(52)
        val maxX = metrics.widthPixels - size
        val maxY = metrics.heightPixels - size - dp(48)
        p.x = p.x.coerceIn(0, maxX)
        p.y = p.y.coerceIn(dp(24), maxY)
    }

    private fun snapToEdge(p: WindowManager.LayoutParams) {
        val metrics = screenMetrics()
        val size = dp(52)
        val margin = dp(10)
        val center = p.x + size / 2
        p.x = if (center < metrics.widthPixels / 2) margin else metrics.widthPixels - size - margin
        clampPosition(p)
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun bottomNavigationInset(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val insets = windowManager.currentWindowMetrics.windowInsets
                .getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars())
                .bottom
            insets
        } else {
            val resourceId =
                resources.getIdentifier("navigation_bar_height", "dimen", "android")
            if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else 0
        }
    }

    private fun screenMetrics(): DisplayMetrics {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            DisplayMetrics().also {
                it.widthPixels = bounds.width()
                it.heightPixels = bounds.height()
                it.densityDpi = resources.displayMetrics.densityDpi
            }
        } else {
            DisplayMetrics().also {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getRealMetrics(it)
            }
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    private fun toast(message: String) {
        mainHandler.post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        isRunning = false
        analysisToken++
        analysisExecutor.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
        removeResultCard()
        bubbleView?.let {
            try {
                windowManager.removeViewImmediate(it)
            } catch (_: Exception) {
            }
        }
        bubbleView = null
        bubbleParams = null
        lastImages = emptyList()
        try {
            mediaProjection?.stop()
        } catch (_: Exception) {
        }
        mediaProjection = null
        if (recognizerDelegate.isInitialized()) {
            try {
                recognizer.close()
            } catch (_: Exception) {
            }
        }
        super.onDestroy()
    }
}

private class MaxHeightScrollView(context: Context) : ScrollView(context) {
    var maxHeight: Int = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (maxHeight > 0) {
            super.onMeasure(
                widthMeasureSpec,
                MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST)
            )
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }
}
