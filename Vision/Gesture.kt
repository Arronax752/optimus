    package com.example.optimus.vision

    import android.app.Notification
    import android.app.NotificationChannel
    import android.app.NotificationManager
    import android.content.Intent
    import android.content.res.Configuration
    import android.graphics.BitmapFactory
    import android.os.Handler
    import android.os.Looper
    import android.os.SystemClock
    import android.provider.Settings
    import android.util.Log
    import android.util.Size
    import android.view.WindowManager
    import androidx.camera.core.CameraSelector
    import androidx.camera.core.ImageAnalysis
    import androidx.camera.core.ImageProxy
    import androidx.camera.lifecycle.ProcessCameraProvider
    import androidx.core.app.NotificationCompat
    import androidx.core.content.ContextCompat
    import androidx.lifecycle.Lifecycle
    import androidx.lifecycle.LifecycleService
    import androidx.lifecycle.lifecycleScope
    import androidx.lifecycle.repeatOnLifecycle
    import com.example.optimus.R
    import com.example.optimus.helper.OptimusAccessibility
    import com.example.optimus.helper.State
    import com.google.mediapipe.framework.image.BitmapImageBuilder
    import com.google.mediapipe.tasks.core.BaseOptions
    import com.google.mediapipe.tasks.vision.core.RunningMode
    import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
    import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
    import kotlinx.coroutines.launch
    import java.util.concurrent.Executors
    import kotlin.math.abs
    import kotlin.math.sqrt

    @Suppress("DEPRECATION")
    class Gesture : LifecycleService() {

        private val CHANNEL_ID = "OptimusGestureService"
        private val NOTIF_ID = 2
        private lateinit var handLandmarker: HandLandmarker
        private val cameraExecutor = Executors.newSingleThreadExecutor()
        private val mainHandler = Handler(Looper.getMainLooper())
        private var lastProcessTime = 0L
        private val FRAME_DELAY = 50L // ~33 FPS
        private lateinit var cursorOverlay: CursorOverlay
        private var cameraProvider: ProcessCameraProvider? = null
        @Volatile
        private var isCameraRunning = false
        @Volatile
        private var isCameraStarting = false
        private var screenWidth = 0
        private var screenHeight = 0
        private var smoothX = 0f
        private var smoothY = 0f
        private var swipeActive = false
        private var swipeAnchorHandX = 0f
        private var swipeAnchorHandY = 0f
        private var lastSwipeTime = 0L
        private var backLocked = false
        private var recentsLocked = false
        private var holdLocked = false
        private var holdTriggered = false
        private var gesturePaused = false
        private var lLockFrames = 0
        private var lUnlockFrames = 0
        private var isLLockStable = false
        private var holdStart = 0L
        private val accessibility get() = OptimusAccessibility.instance
        private val PINCH_THRESHOLD = 0.045f
        private val PINCH_RELEASE = 0.075f
        private var lastHomeTime = 0L
        private val HOME_COOLDOWN = 1000L
        private val HOLD_TIME = 500L
        private val SWIPE_ACTION_THRESHOLD = 260f
        private val SWIPE_COOLDOWN = 800L
        private val CURSOR_OFFSET_Y = 25f
        private var dragStartX = 0f
        private var dragStartY = 0f
        private var lastFunnySoundTime = 0L
        private val FUNNY_COOLDOWN = 1500L
        private val funnySounds = listOf(
            "1_.mp3",
            "2_.mp3",
            "3_.mp3",
            "4_.mp3"
        )
        override fun onCreate() {
            super.onCreate()
            createNotificationChannel()
            startForeground(NOTIF_ID, createNotification())
            val metrics = resources.displayMetrics
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
            initHandLandmarker()
            State.visionEnabled.value = true
        }

        override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
            super.onStartCommand(intent, flags, startId)
            if (!Settings.canDrawOverlays(this)) {
                stopSelf()
                return START_NOT_STICKY
            }
            if (!::cursorOverlay.isInitialized) {
                cursorOverlay = CursorOverlay(this)
            }
            lifecycleScope.launch {
                lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    State.visionEnabled.collect { enabled ->
                        if (enabled) safeStartCamera()
                        else {
                            cameraProvider?.unbindAll()
                            isCameraRunning = false
                        }
                    }
                }
            }

            return START_STICKY
        }

        private fun startCamera() {
            if (isCameraStarting) return
            isCameraStarting = true
            val providerFuture = ProcessCameraProvider.getInstance(this)
            providerFuture.addListener({
                try {
                    val provider = providerFuture.get()
                    cameraProvider = provider
                    val isPortrait = screenHeight > screenWidth
                    val display = (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay
                    val analyzer = ImageAnalysis.Builder()
                        .setTargetResolution(
                            if (isPortrait) Size(480, 640)
                            else Size(640, 480)
                        )
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build()
                    analyzer.targetRotation = display.rotation
                    analyzer.setAnalyzer(cameraExecutor) { image ->
                        processFrame(image)
                    }
                    provider.unbindAll()
                    provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analyzer)
                    isCameraRunning = true
                } catch (_: Exception) {
                    mainHandler.postDelayed({ safeStartCamera() }, 1000)
                } finally {
                    isCameraStarting = false
                }
            }, ContextCompat.getMainExecutor(this))
        }

        override fun onConfigurationChanged(newConfig: Configuration) {
            super.onConfigurationChanged(newConfig)

            val metrics = resources.displayMetrics
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
            smoothX = screenWidth / 2f
            smoothY = screenHeight / 2f
            Log.i("GESTURE", "Rotation changed → Rebinding camera")

            rebindCamera()
        }

        private fun rebindCamera() {
            val provider = cameraProvider ?: return
            provider.unbindAll()
            startCamera() // Restart with new orientation context
        }

        private fun safeStartCamera() {
            if (isCameraRunning || isCameraStarting) return

            try {
                startCamera()
                isCameraRunning = true
            } catch (e: Exception) {
                Log.e("GESTURE", "Camera crash → restarting", e)

                isCameraRunning = false

                mainHandler.postDelayed({
                    safeStartCamera()
                }, 1000)
            }
        }

        override fun onDestroy() {
            super.onDestroy()
            try {
                cameraProvider?.unbindAll()
                cameraExecutor.shutdownNow()
                mainHandler.removeCallbacksAndMessages(null)
                if (::handLandmarker.isInitialized) {
                    handLandmarker.close()
                }
                if (::cursorOverlay.isInitialized) {
                    cursorOverlay.remove()
                }
            } catch (_: Exception) {
            }
            State.visionEnabled.value = false
        }

        private fun createNotificationChannel() {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Optimus Vision",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }

        private fun createNotification(): Notification {
            return NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Vision")
                .setContentText("Gesture system active")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setLargeIcon(BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher))
                .setOngoing(true)
                .setSilent(true)
                .build()
        }

        private fun initHandLandmarker() {
            val baseOptions = BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build()
            val options =
                HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setNumHands(1)
                    .setMinTrackingConfidence(0.5f)
                    .setMinHandDetectionConfidence(0.6f)
                    .setMinHandPresenceConfidence(0.6f)
                    .setResultListener { result, _ -> processGestures(result) }
                    .build()
            handLandmarker = HandLandmarker.createFromOptions(this, options)
        }

        private fun processFrame(image: ImageProxy) {
            val now = SystemClock.uptimeMillis()

            if (now - lastProcessTime < FRAME_DELAY) {
                image.close()
                return
            }
            lastProcessTime = now

            try {
                val bitmap = image.toBitmapSafe()
                if (bitmap == null) return

                if (::handLandmarker.isInitialized) {
                    val mpImage = BitmapImageBuilder(bitmap).build()
                    handLandmarker.detectAsync(mpImage, now)
                }

            } catch (e: Exception) {
                Log.e("GESTURE", "Frame processing failed", e)
            } finally {
                image.close()
            }
        }

        private var currentPlayer: android.media.MediaPlayer? = null
        private fun playFunnySound() {
            if (gesturePaused) return
            try {
                gesturePaused = true
                currentPlayer?.release()
                val file = funnySounds.random()
                val afd = assets.openFd(file)
                val mediaPlayer = android.media.MediaPlayer()
                currentPlayer = mediaPlayer
                mediaPlayer.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                mediaPlayer.setOnPreparedListener {
                    it.start()
                }
                mediaPlayer.setOnCompletionListener {
                    cleanupPlayer()
                }
                mediaPlayer.setOnErrorListener { mp, _, _ ->
                    mp.release()
                    cleanupPlayer()
                    true }
                mediaPlayer.prepareAsync()
                mainHandler.postDelayed({
                    if (gesturePaused && currentPlayer != null) {
                        cleanupPlayer()
                    }
                }, 4000)   // increased

            } catch (_: Exception) {
                cleanupPlayer()
            }
        }
        private fun cleanupPlayer() {
            currentPlayer?.release()
            currentPlayer = null
            gesturePaused = false
            Log.i("GESTURE", "✅ Sound finished → gestures resumed")
        }

        private fun processGestures(result: HandLandmarkerResult) {
            if (gesturePaused) return
            if (result.landmarks().isEmpty()) {
                swipeActive = false
                return
            }
            val hand = result.landmarks()[0]
            val now = System.currentTimeMillis()
            val wrist = hand[0]
            val thumbTip = hand[4]
            val indexTip = hand[8]
            val middleTip = hand[12]
            val ringTip = hand[15]
            val pinkyTip = hand[20]
            val indexMcp = hand[5]
            val middleMcp = hand[9]
            val ringMcp = hand[13]
            val pinkyMcp = hand[17]
            fun dist(ax: Float, ay: Float, bx: Float, by: Float): Float {
                val dx = ax - bx
                val dy = ay - by
                return sqrt(dx * dx + dy * dy)
            }
            val pinch = dist(thumbTip.x(), thumbTip.y(), indexTip.x(), indexTip.y())
            val thumbMiddle = dist(thumbTip.x(), thumbTip.y(), middleTip.x(), middleTip.y())
            val thumbPinky = dist(thumbTip.x(), thumbTip.y(), pinkyTip.x(), pinkyTip.y())
            val thumbRingBase = dist(thumbTip.x(), thumbTip.y(), hand[13].x(), hand[13].y())
            val middleExtended = middleTip.y() < middleMcp.y() - 0.08f
            val indexTucked = indexTip.y() > indexMcp.y() && indexTip.z() > indexMcp.z()
            val avgFingerZ = (indexTip.z() + middleTip.z() + ringTip.z()) / 3f
            val isBackHand = avgFingerZ > wrist.z() + 0.05f
            val indexExtended = indexTip.y() < indexMcp.y() - 0.05f
            val thumbExtended = dist(
                thumbTip.x(), thumbTip.y(),
                indexMcp.x(), indexMcp.y()
            ) > 0.12f
            val middleTucked = middleTip.y() > middleMcp.y() - 0.01f
            val ringTucked = ringTip.y() > ringMcp.y() - 0.01f
            val pinkyTucked = pinkyTip.y() > pinkyMcp.y() - 0.01f
            val isLLockRaw = indexExtended && thumbExtended && middleTucked && ringTucked && pinkyTucked
            if (isLLockRaw) {
                lLockFrames++
                lUnlockFrames = 0
            } else { lUnlockFrames++;lLockFrames = 0 }
            // Enter lock after 3 stable frames (~100ms)
            if (!isLLockStable && lLockFrames > 4) {
                isLLockStable = true
            }
            // Exit lock after 4 missing frames
            if (isLLockStable && lUnlockFrames > 5) {
                isLLockStable = false
            }
            if (middleExtended && indexTucked && ringTucked && pinkyTucked) {
                if (now - lastFunnySoundTime > FUNNY_COOLDOWN) {
                    lastFunnySoundTime = now
                    playFunnySound()
                }
                return
            }
            if (isBackHand) {
                mainHandler.post { cursorOverlay.setRed() }
                swipeActive = false
                return
            } else if (!isLLockStable) {
                mainHandler.post { cursorOverlay.setBlue() }
            }
            if (isLLockRaw) {
                val handX = (wrist.x() + middleMcp.x()) / 2f
                val handY = (wrist.y() + middleMcp.y()) / 2f
                mainHandler.post { cursorOverlay.setGreen() }
                if (!swipeActive) {
                    swipeActive = true
                    swipeAnchorHandX = handX
                    swipeAnchorHandY = handY
                } else {
                    val dx = (handX - swipeAnchorHandX) * screenWidth * 2.2f
                    val dy = (handY - swipeAnchorHandY) * screenHeight * 2.2f

                    if (now - lastSwipeTime > SWIPE_COOLDOWN) {
                        if (abs(dx) > SWIPE_ACTION_THRESHOLD || abs(dy) > SWIPE_ACTION_THRESHOLD) {

                            if (abs(dx) > abs(dy)) {
                                if (dx > 0) {
                                    accessibility?.swipeRight()
                                } else {
                                    accessibility?.swipeLeft()
                                }
                            } else {
                                if (dy > 0) {
                                    accessibility?.scrollDown()
                                } else {
                                    accessibility?.scrollUp()
                                }
                            }
                            lastSwipeTime = now
                            swipeAnchorHandX = handX
                            swipeAnchorHandY = handY
                        }
                    }
                }
                return
            }
            swipeActive = false
            if (thumbMiddle < 0.045f) {
                if (now - lastHomeTime > HOME_COOLDOWN) {
                    lastHomeTime = now
                    accessibility?.homeScreen()
                    Log.i("GESTURE", "HOME")
                }
                return
            }
            // ================= BACK
            if (thumbRingBase < 0.045f) {
                if (!backLocked) {
                    backLocked = true
                    accessibility?.goBack()
                    Log.i("GESTURE", "BACK")
                }
                return
            } else backLocked = false
            // ================= RECENTS
            if (thumbPinky < 0.045f) {

                if (!recentsLocked) {
                    recentsLocked = true
                    accessibility?.recents()
                    Log.i("GESTURE", "RECENTS")
                }
                return
            } else recentsLocked = false
            // ================= CLICK + HOLD
            if (!holdLocked && pinch < PINCH_THRESHOLD) {
                holdLocked = true
                holdStart = now

                dragStartX = smoothX
                dragStartY = smoothY
            }
            if (holdLocked) {
                val pressDuration = now - holdStart
                // ================= HOLD
                if (!holdTriggered && pressDuration > HOLD_TIME) {
                    accessibility?.longPress(dragStartX, dragStartY + CURSOR_OFFSET_Y)
                    holdTriggered = true
                }
                if (pinch > PINCH_RELEASE) {
                    if (!holdTriggered) {
                        accessibility?.performClick(dragStartX, dragStartY + CURSOR_OFFSET_Y)
                    }
                    holdLocked = false
                    holdTriggered = false
                }
                return
            }

            // CURSOR
            val palmX = (wrist.x() + indexMcp.x() + middleMcp.x() + ringMcp.x()) / 4f
            val palmY = (wrist.y() + indexMcp.y() + middleMcp.y() + ringMcp.y()) / 4f
            fun expand(v: Float): Float {
                val min = 0.15f
                val max = 0.86f
                return ((v - min) / (max - min)).coerceIn(0f, 1f)
            }
            val mappedX = expand(palmX)
            val mappedY = expand(palmY)
            val targetX = mappedX * screenWidth
            val targetY = mappedY * screenHeight
            val dx = targetX - smoothX
            val dy = targetY - smoothY
            val distance = sqrt(dx * dx + dy * dy)
            val alpha = when {
                distance > 200 -> 0.45f
                distance > 80  -> 0.28f
                distance > 20  -> 0.18f
                else           -> 0.08f
            }
            if (distance < 2f) return
            smoothX += dx * alpha
            smoothY += dy * alpha
            mainHandler.post { cursorOverlay.move(smoothX, smoothY) }
        }
    }
