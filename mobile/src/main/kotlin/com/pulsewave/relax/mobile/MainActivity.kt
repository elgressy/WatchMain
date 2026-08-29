package com.pulsewave.relax.mobile

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable

private const val HR_PATH = "/hr"

/**
 * Fullscreen WebView shell that hosts the relaxation experience and forwards
 * heart-rate readings received from the paired Wear OS app (Wearable Data
 * Layer, message path "/hr") into the page via window.onExternalBpm(bpm).
 */
class MainActivity : ComponentActivity(), MessageClient.OnMessageReceivedListener {

    private lateinit var webView: WebView

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
        setContentView(webView)
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
