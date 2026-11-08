package com.elgressy.pitput

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

private const val APP_URL = "https://appassets.androidplatform.net/assets/index.html"

/**
 * Fullscreen WebView shell for the Pitput web app (assets/index.html).
 *
 * The page is served through WebViewAssetLoader from a secure https origin so that
 * getUserMedia (mic for "your turn" mode, camera for word photos), MediaRecorder,
 * IndexedDB and localStorage all behave like in Chrome. Android's WebView has no
 * speechSynthesis, so Hebrew TTS is bridged to the platform TextToSpeech engine.
 */
class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private var tts: TextToSpeech? = null
    @Volatile private var ttsState = "init" // init | ready | no-hebrew | error

    private var pendingPermission: PermissionRequest? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> resolvePendingPermission() }

    private val fileLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val cb = fileCallback
        fileCallback = null
        cb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        initTts()

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            settings.textZoom = 100
            overScrollMode = View.OVER_SCROLL_NEVER
            setBackgroundColor(0xFF1B1150.toInt())
            addJavascriptInterface(NativeBridge(), "PitputNative")

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    // Keep the toddler inside the app: never navigate away.
                    return request.url.host != "appassets.androidplatform.net"
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) {
                    runOnUiThread { handleWebPermission(request) }
                }

                override fun onShowFileChooser(
                    webView: WebView,
                    filePathCallback: ValueCallback<Array<Uri>>,
                    fileChooserParams: FileChooserParams
                ): Boolean {
                    fileCallback?.onReceiveValue(null)
                    fileCallback = filePathCallback
                    return try {
                        fileLauncher.launch(fileChooserParams.createIntent())
                        true
                    } catch (e: Exception) {
                        fileCallback = null
                        false
                    }
                }
            }
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(0xFF1B1150.toInt())
            addView(webView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        setContentView(root)

        // Android 15 forces edge-to-edge: pad the container so the page never sits
        // under the status/nav bars or the keyboard.
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                webView.evaluateJavascript("(window.pitputBack ? window.pitputBack() : false)") { r ->
                    if (r != "true") finish()
                }
            }
        })

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl(APP_URL)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        stopAsr()
        tts?.stop()
        webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onDestroy() {
        stopAsr()
        tts?.shutdown()
        tts = null
        webView.destroy()
        super.onDestroy()
    }

    // ------------------------------------- speech recognition ("your turn": did he say the word?)
    // Uses the phone's recognition service (Google, he-IL). JS gets events via window.__asr(id, type, payload):
    // "speech", "rms" (0..1), "partial" [..], "result" [..], "error" <SpeechRecognizer.ERROR_* code>.

    private var recognizer: SpeechRecognizer? = null
    private var asrId: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var asrTimeout: Runnable? = null
    private var lastRmsAt = 0L
    private var pendingAsr: Pair<String, Long>? = null

    private val audioPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok ->
        val p = pendingAsr
        pendingAsr = null
        if (p != null) {
            if (ok) startAsr(p.first, p.second) else emitAsr(p.first, "error", "9")
        }
    }

    private fun emitAsr(id: String, type: String, payloadJson: String) {
        val js = "window.__asr && window.__asr(${JSONObject.quote(id)}, ${JSONObject.quote(type)}, $payloadJson)"
        runOnUiThread { if (::webView.isInitialized) webView.evaluateJavascript(js, null) }
    }

    private fun resultsJson(b: Bundle?): String =
        JSONArray(b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf<String>()).toString()

    private fun startAsr(id: String, maxMs: Long) {
        stopAsr()
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            emitAsr(id, "error", "5")
            return
        }
        if (!granted(Manifest.permission.RECORD_AUDIO)) {
            pendingAsr = id to maxMs
            audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        val rec = try { SpeechRecognizer.createSpeechRecognizer(this) } catch (e: Exception) { null }
        if (rec == null) {
            emitAsr(id, "error", "5")
            return
        }
        recognizer = rec
        asrId = id
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {
                if (asrId == id) emitAsr(id, "speech", "true")
            }
            override fun onRmsChanged(rmsdB: Float) {
                val now = System.currentTimeMillis()
                if (asrId == id && now - lastRmsAt > 70) {
                    lastRmsAt = now
                    val v = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                    emitAsr(id, "rms", String.format(Locale.US, "%.2f", v))
                }
            }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                if (asrId == id) {
                    emitAsr(id, "error", error.toString())
                    finishAsr(id)
                }
            }
            override fun onResults(results: Bundle?) {
                if (asrId == id) {
                    emitAsr(id, "result", resultsJson(results))
                    finishAsr(id)
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {
                if (asrId == id) emitAsr(id, "partial", resultsJson(partialResults))
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "he-IL")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "he-IL")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        try {
            rec.startListening(intent)
        } catch (e: Exception) {
            emitAsr(id, "error", "5")
            finishAsr(id)
            return
        }
        val t = Runnable { if (asrId == id) try { recognizer?.stopListening() } catch (_: Exception) {} }
        asrTimeout = t
        mainHandler.postDelayed(t, maxMs)
    }

    /** Release after a final callback (posted, so we never destroy from inside the listener call). */
    private fun finishAsr(id: String) {
        mainHandler.post { if (asrId == id) stopAsr() }
    }

    private fun stopAsr() {
        asrTimeout?.let { mainHandler.removeCallbacks(it) }
        asrTimeout = null
        asrId = null
        val rec = recognizer
        recognizer = null
        if (rec != null) {
            try { rec.cancel() } catch (_: Exception) {}
            try { rec.destroy() } catch (_: Exception) {}
        }
    }

    // ------------------------------------------------------------------ TTS

    private fun initTts() {
        tts = TextToSpeech(applicationContext) { status ->
            val engine = tts
            if (status != TextToSpeech.SUCCESS || engine == null) {
                ttsState = "error"
                return@TextToSpeech
            }
            val result = engine.setLanguage(Locale("he", "IL"))
            ttsState = if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                // Some engines still register Hebrew under the legacy "iw" code.
                val legacy = engine.setLanguage(Locale("iw", "IL"))
                if (legacy == TextToSpeech.LANG_MISSING_DATA || legacy == TextToSpeech.LANG_NOT_SUPPORTED) "no-hebrew" else "ready"
            } else "ready"

            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) = notifyDone(utteranceId)
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = notifyDone(utteranceId)
                override fun onError(utteranceId: String?, errorCode: Int) = notifyDone(utteranceId)
                override fun onStop(utteranceId: String?, interrupted: Boolean) = notifyDone(utteranceId)
            })
        }
    }

    private fun notifyDone(id: String?) {
        if (id == null) return
        val js = "window.__ttsDone && window.__ttsDone(${JSONObject.quote(id)})"
        runOnUiThread { if (::webView.isInitialized) webView.evaluateJavascript(js, null) }
    }

    // ------------------------------------------------------- web permissions

    private fun handleWebPermission(request: PermissionRequest) {
        pendingPermission?.deny()
        pendingPermission = request
        val needed = mutableListOf<String>()
        for (res in request.resources) {
            when (res) {
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> if (!granted(Manifest.permission.RECORD_AUDIO)) needed += Manifest.permission.RECORD_AUDIO
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> if (!granted(Manifest.permission.CAMERA)) needed += Manifest.permission.CAMERA
            }
        }
        if (needed.isEmpty()) resolvePendingPermission() else permissionLauncher.launch(needed.toTypedArray())
    }

    private fun resolvePendingPermission() {
        val req = pendingPermission ?: return
        pendingPermission = null
        val ok = req.resources.filter { res ->
            when (res) {
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> granted(Manifest.permission.RECORD_AUDIO)
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> granted(Manifest.permission.CAMERA)
                else -> false
            }
        }
        if (ok.isEmpty()) req.deny() else req.grant(ok.toTypedArray())
    }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    // ---------------------------------------------------------- JS bridge

    inner class NativeBridge {
        @JavascriptInterface
        fun speak(text: String, rate: Float, pitch: Float, id: String) {
            val engine = tts
            if (engine == null || ttsState == "error" || ttsState == "init") {
                notifyDone(id)
                return
            }
            engine.setSpeechRate(rate.coerceIn(0.4f, 1.6f))
            engine.setPitch(pitch.coerceIn(0.6f, 1.6f))
            val r = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
            if (r != TextToSpeech.SUCCESS) notifyDone(id)
        }

        @JavascriptInterface
        fun stopSpeaking() {
            tts?.stop()
        }

        @JavascriptInterface
        fun ttsStatus(): String = ttsState

        @JavascriptInterface
        fun asrAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(this@MainActivity)

        @JavascriptInterface
        fun asrStart(id: String, maxMs: Int) {
            runOnUiThread { startAsr(id, maxMs.toLong().coerceIn(1500L, 15000L)) }
        }

        @JavascriptInterface
        fun asrStop() {
            runOnUiThread { stopAsr() }
        }

        @JavascriptInterface
        fun openTtsSettings() {
            runOnUiThread {
                try {
                    startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                    try {
                        startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
                    } catch (_: Exception) {
                        Toast.makeText(this@MainActivity, "לא נמצאו הגדרות הקראה", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        @JavascriptInterface
        fun setKeepScreenOn(on: Boolean) {
            runOnUiThread {
                if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        /** Writes [content] to cache and opens the system share sheet (e.g. WhatsApp to the other parent). */
        @JavascriptInterface
        fun shareFile(fileName: String, content: String) {
            try {
                val dir = File(cacheDir, "share").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
                val file = File(dir, safeName)
                file.writeText(content, Charsets.UTF_8)
                val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.fileprovider", file)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, safeName)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runOnUiThread {
                    startActivity(Intent.createChooser(send, getString(R.string.share_title)))
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "השיתוף נכשל: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
