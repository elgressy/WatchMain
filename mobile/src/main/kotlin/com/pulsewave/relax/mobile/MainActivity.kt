package com.pulsewave.relax.mobile

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import kotlin.math.roundToInt

private const val HR_PATH = "/hr"

/**
 * Fullscreen WebView shell that hosts the relaxation experience and forwards
 * heart-rate readings into it via window.onExternalBpm(bpm) — either from
 * the paired Wear OS app (Wearable Data Layer, message path "/hr") or from
 * a one-off camera-based measurement (CameraHeartRateActivity) when no
 * watch is available.
 */
class MainActivity : ComponentActivity(), MessageClient.OnMessageReceivedListener {

    private lateinit var webView: WebView

    private val cameraMeasureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val bpm = result.data?.getDoubleExtra(CameraHeartRateActivity.EXTRA_BPM, -1.0) ?: -1.0
        if (result.resultCode == RESULT_OK && bpm > 0) {
            webView.evaluateJavascript("window.onExternalBpm($bpm)", null)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableImmersiveMode()

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webViewClient = WebViewClient()
            loadUrl("file:///android_asset/index.html")
        }

        val cameraButton = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_camera)
            background = circularButtonBackground()
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(6), dp(6), dp(6), dp(6))
            contentDescription = getString(R.string.camera_measure_button)
            setOnClickListener {
                cameraMeasureLauncher.launch(Intent(this@MainActivity, CameraHeartRateActivity::class.java))
            }
        }

        val root = FrameLayout(this).apply {
            addView(webView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(
                cameraButton,
                FrameLayout.LayoutParams(dp(30), dp(30)).apply {
                    gravity = Gravity.TOP or Gravity.END
                    topMargin = dp(16)
                    marginEnd = dp(14)
                }
            )
        }
        setContentView(root)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    private fun circularButtonBackground(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(0xF2FFFFFF.toInt())
        setStroke(dp(1), 0x261C2B45)
    }

    private fun enableImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onResume() {
        super.onResume()
        Wearable.getMessageClient(this).addListener(this)
    }

    override fun onPause() {
        Wearable.getMessageClient(this).removeListener(this)
        super.onPause()
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != HR_PATH) return
        val bpm = String(event.data, Charsets.UTF_8).toFloatOrNull() ?: return
        runOnUiThread {
            webView.evaluateJavascript("window.onExternalBpm($bpm)", null)
        }
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
