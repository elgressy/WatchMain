package com.pulsewave.relax.mobile

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.util.Size
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Finger-over-camera-and-flash heart-rate measurement, for when no watch is
 * paired. Runs a fixed measurement window, streams a live BPM guess to the
 * UI, and returns the final estimate to the caller via EXTRA_BPM.
 */
class CameraHeartRateActivity : ComponentActivity() {

    companion object {
        const val EXTRA_BPM = "bpm"
        private const val MEASURE_DURATION_MS = 20_000L
    }

    private lateinit var statusText: TextView
    private lateinit var bpmText: TextView

    private val processor = PpgSignalProcessor()
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var camera: Camera? = null
    private var startedAt = 0L
    private var finished = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startCamera() else statusText.text = getString(R.string.camera_permission_denied) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun buildUi() {
        statusText = TextView(this).apply {
            text = getString(R.string.camera_instructions)
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        bpmText = TextView(this).apply {
            text = "--"
            textSize = 56f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        val cancelButton = Button(this).apply {
            text = getString(R.string.cancel)
            setOnClickListener { finishWithResult(null) }
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            addView(
                statusText,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = dp(24) }
            )
            addView(
                bpmText,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = dp(24) }
            )
            addView(cancelButton)
        }
        setContentView(
            FrameLayout(this).apply {
                setBackgroundColor(Color.BLACK)
                addView(layout, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
        )
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    private fun startCamera() {
        startedAt = SystemClock.elapsedRealtime()
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(160, 120))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(cameraExecutor) { image -> analyzeFrame(image) }

            provider.unbindAll()
            camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
            camera?.cameraControl?.enableTorch(true)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFrame(image: ImageProxy) {
        try {
            if (finished) return
            val buffer = image.planes[0].buffer // Y (luma) plane
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)

            var sum = 0L
            var count = 0
            var i = 0
            while (i < bytes.size) { // sample every 4th byte; plenty for a mean-luma estimate
                sum += bytes[i].toInt() and 0xFF
                count++
                i += 4
            }
            val meanLuma = if (count > 0) sum.toDouble() / count else 0.0

            val now = SystemClock.elapsedRealtime()
            processor.addSample(now, meanLuma)
            val elapsed = now - startedAt
            val estimate = processor.estimateBpm()

            runOnUiThread {
                if (estimate != null) bpmText.text = estimate.roundToInt().toString()
                val remainingSec = ((MEASURE_DURATION_MS - elapsed) / 1000).coerceAtLeast(0)
                statusText.text = getString(R.string.camera_measuring, remainingSec)
            }

            if (elapsed >= MEASURE_DURATION_MS) finishWithResult(estimate)
        } finally {
            image.close()
        }
    }

    private fun finishWithResult(bpm: Double?) {
        if (finished) return
        finished = true
        camera?.cameraControl?.enableTorch(false)
        runOnUiThread {
            if (bpm != null) {
                setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_BPM, bpm))
            } else {
                setResult(Activity.RESULT_CANCELED)
            }
            finish()
        }
    }

    override fun onDestroy() {
        camera?.cameraControl?.enableTorch(false)
        cameraExecutor.shutdown()
        super.onDestroy()
    }
}
