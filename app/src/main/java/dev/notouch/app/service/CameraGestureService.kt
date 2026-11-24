package dev.notouch.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import dev.notouch.app.MainActivity
import dev.notouch.app.R
import dev.notouch.app.Settings
import dev.notouch.app.gesture.FaceFrame
import dev.notouch.app.gesture.GestureEngine
import dev.notouch.app.gesture.Mode
import dev.notouch.app.gesture.Pt
import java.util.concurrent.Executors

/**
 * Owns the front camera (a camera-type foreground service, so it keeps working with the app in the
 * background), runs MediaPipe hand / face landmarkers on-device, feeds the [GestureEngine] and hands
 * the result to [NoTouchAccessibilityService]. The notification carries Pause / Stop so the user
 * can always kill it.
 */
class CameraGestureService : LifecycleService() {

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val engine = GestureEngine(Mode.BOTH)
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null

    // Touched only on analysisExecutor.
    private var handLandmarker: HandLandmarker? = null
    private var faceLandmarker: FaceLandmarker? = null
    private var lastTimestamp = 0L
    private var loadedGpu = false
    private var delegateName = "CPU"
    private var fps = 0f
    private var frameCount = 0L

    @Volatile private var paused = false
    @Volatile private var mode = Mode.BOTH

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_PAUSE -> {
                paused = !paused
                updateNotification() // notify() only: startForeground() is not re-called from the background
                if (paused) pauseCamera() else bindCamera()
            }
            ACTION_RECENTER -> analysisExecutor.execute { engine.recenterFace() }
            else -> { // ACTION_START (always sent while the app is visible)
                mode = Settings.mode(this)
                val m = mode
                paused = false
                analysisExecutor.execute { engine.mode = m; engine.reset() } // engine belongs to the analysis thread
                if (!startForegroundNow()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                prepareModels()
                bindCamera()
            }
        }
        return START_NOT_STICKY // never silently restart a camera service
    }

    override fun onDestroy() {
        running = false
        hideCursor()
        provider?.unbindAll()
        analysis?.clearAnalyzer()
        analysisExecutor.execute {
            handLandmarker?.close(); handLandmarker = null
            faceLandmarker?.close(); faceLandmarker = null
        }
        analysisExecutor.shutdown()
        super.onDestroy()
    }

    // ---- camera ---------------------------------------------------------------------

    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val p = future.get()
            provider = p
            val useCase = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also { it.setAnalyzer(analysisExecutor, ::analyze) }
            analysis = useCase
            p.unbindAll()
            if (!paused) {
                runCatching { p.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, useCase) }
                    .onFailure { stopSelf() } // e.g. camera in use / not allowed in background
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun pauseCamera() {
        provider?.unbindAll()
        analysis?.clearAnalyzer()
        analysisExecutor.execute { engine.reset() }
        hideCursor()
    }

    // ---- models ---------------------------------------------------------------------

    private fun prepareModels() {
        val m = mode
        val gpu = Settings.useGpu(this)
        analysisExecutor.execute {
            try {
                if (gpu != loadedGpu) { // delegate changed: reload from scratch
                    handLandmarker?.close(); handLandmarker = null
                    faceLandmarker?.close(); faceLandmarker = null
                    loadedGpu = gpu
                }
                loadModels(m, gpu)
            } catch (t: Throwable) {
                Log.e(TAG, "Could not load MediaPipe models", t)
                ContextCompat.getMainExecutor(this).execute { stopSelf() }
            }
        }
    }

    private fun loadModels(m: Mode, gpu: Boolean) {
        fun base(asset: String, delegate: Delegate) =
            BaseOptions.builder().setModelAssetPath(asset).setDelegate(delegate).build()

        // Try the requested delegate; fall back to CPU if it can't be created.
        fun <T> create(build: (Delegate) -> T): T {
            if (gpu) {
                try {
                    return build(Delegate.GPU).also { delegateName = "GPU" }
                } catch (t: Throwable) {
                    Log.w(TAG, "GPU delegate unavailable, using CPU", t)
                }
            }
            delegateName = "CPU"
            return build(Delegate.CPU)
        }

        if (m != Mode.FACE && handLandmarker == null) {
            handLandmarker = create { d ->
                HandLandmarker.createFromOptions(
                    this,
                    HandLandmarker.HandLandmarkerOptions.builder()
                        .setBaseOptions(base("hand_landmarker.task", d))
                        .setRunningMode(RunningMode.VIDEO)
                        .setNumHands(1)
                        // Defaults are 0.5; lower values keep tracking in dim light / odd angles.
                        .setMinHandDetectionConfidence(0.3f)
                        .setMinHandPresenceConfidence(0.3f)
                        .setMinTrackingConfidence(0.3f)
                        .build(),
                )
            }
        }
        if (m != Mode.HAND && faceLandmarker == null) {
            faceLandmarker = create { d ->
                FaceLandmarker.createFromOptions(
                    this,
                    FaceLandmarker.FaceLandmarkerOptions.builder()
                        .setBaseOptions(base("face_landmarker.task", d))
                        .setRunningMode(RunningMode.VIDEO)
                        .setNumFaces(1)
                        .setMinFaceDetectionConfidence(0.3f)
                        .setMinFacePresenceConfidence(0.3f)
                        .setMinTrackingConfidence(0.3f)
                        .setOutputFaceBlendshapes(true) // off by default; the expression gestures need it
                        .build(),
                )
            }
        }
    }

    // ---- per-frame pipeline (analysisExecutor) ----------------------------------------

    private fun analyze(image: ImageProxy) {
        var raw: Bitmap? = null
        var upright: Bitmap? = null
        try {
            if (paused) return
            val hand = if (mode != Mode.FACE) handLandmarker else null
            val face = if (mode != Mode.HAND) faceLandmarker else null
            if (hand == null && face == null) return // models still loading

            raw = image.toBitmap()
            upright = toUprightMirrored(raw, image.imageInfo.rotationDegrees)
            val mpImage = BitmapImageBuilder(upright).build()

            val sens = Settings.sensitivity(this)
            engine.faceGainScale = sens
            engine.bindings = Settings.bindingsFor(this, NoTouchAccessibilityService.foregroundPackage)
            var t = SystemClock.uptimeMillis()
            if (t <= lastTimestamp) t = lastTimestamp + 1 // MediaPipe needs strictly increasing timestamps
            if (lastTimestamp != 0L) {
                val inst = 1000f / (t - lastTimestamp).coerceAtLeast(1)
                fps = if (fps == 0f) inst else fps + 0.1f * (inst - fps)
            }
            lastTimestamp = t
            frameCount++

            val handPts: List<Pt>? = hand?.detectForVideo(mpImage, t)
                ?.landmarks()?.firstOrNull()
                ?.map { Pt(it.x(), it.y()) }

            val faceFrame: FaceFrame? = face?.detectForVideo(mpImage, t)?.let { r ->
                val nose = r.faceLandmarks().firstOrNull()?.getOrNull(NOSE_TIP) ?: return@let null
                // The sensitivity setting scales expression scores so weaker expressions still register.
                val blend = r.faceBlendshapes().orElse(null)?.firstOrNull()
                    ?.associate { (it.categoryName() ?: "") to (it.score() * sens).coerceAtMost(1f) }
                    ?: emptyMap()
                FaceFrame(Pt(nose.x(), nose.y()), blend)
            }

            val out = engine.process(t, handPts, faceFrame)
            NoTouchAccessibilityService.instance?.apply(out.cursor, out.commands)
            if (out.commands.isNotEmpty()) {
                DebugBus.lastCommand = out.commands.joinToString { it.toString() }
                DebugBus.lastCommandAt = t
            }
            if (DebugBus.enabled && frameCount % 2 == 0L) {
                val w = 240
                val small = Bitmap.createScaledBitmap(upright, w, upright.height * w / upright.width, false)
                DebugBus.snapshot = DebugBus.Snapshot(
                    frame = small,
                    hand = handPts,
                    nose = faceFrame?.nose,
                    blend = faceFrame?.blend ?: emptyMap(),
                    pose = if (handPts != null) engine.handPose.name else "none",
                    fps = fps,
                    delegate = delegateName,
                    cursor = out.cursor,
                )
            }
        } catch (e: Exception) {
            // A bad frame must never take the service down, but don't hide it either.
            Log.w(TAG, "Frame skipped", e)
        } finally {
            if (upright !== raw) upright?.recycle()
            raw?.recycle()
            image.close()
        }
    }

    /**
     * The single place that maps camera pixels to the space the recognisers expect:
     * rotate by the sensor rotation so the image is upright, then mirror horizontally
     * (front camera, selfie-style) so moving your hand right moves x up.
     */
    private fun toUprightMirrored(src: Bitmap, rotationDegrees: Int): Bitmap {
        val m = Matrix().apply {
            postRotate(rotationDegrees.toFloat())
            postScale(-1f, 1f)
        }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    private fun hideCursor() {
        NoTouchAccessibilityService.instance?.apply(null, emptyList())
    }

    // ---- notification -----------------------------------------------------------------

    private fun startForegroundNow(): Boolean {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
            running = true
            true
        } catch (e: Exception) { // e.g. started from a state where camera access is not allowed
            Log.e(TAG, "startForeground failed", e)
            false
        }
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(if (paused) R.string.notif_paused else R.string.notif_running))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(
                0,
                getString(if (paused) R.string.notif_resume else R.string.notif_pause),
                servicePending(ACTION_TOGGLE_PAUSE, 1),
            )
        if (mode != Mode.HAND) builder.addAction(0, getString(R.string.notif_recenter), servicePending(ACTION_RECENTER, 3))
        builder.addAction(0, getString(R.string.notif_stop), servicePending(ACTION_STOP, 2))
        return builder.build()
    }

    private fun servicePending(action: String, code: Int) = PendingIntent.getService(
        this, code, Intent(this, CameraGestureService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val ACTION_START = "dev.notouch.app.START"
        const val ACTION_STOP = "dev.notouch.app.STOP"
        const val ACTION_TOGGLE_PAUSE = "dev.notouch.app.TOGGLE_PAUSE"
        const val ACTION_RECENTER = "dev.notouch.app.RECENTER"
        private const val TAG = "NoTouch"
        private const val CHANNEL = "active"
        private const val NOTIFICATION_ID = 1
        private const val NOSE_TIP = 1 // index in the 478-point face mesh

        @Volatile
        var running = false
            private set

        fun start(context: Context) = ContextCompat.startForegroundService(
            context, Intent(context, CameraGestureService::class.java).setAction(ACTION_START),
        )

        fun stop(context: Context) {
            context.startService(Intent(context, CameraGestureService::class.java).setAction(ACTION_STOP))
        }
    }
}
