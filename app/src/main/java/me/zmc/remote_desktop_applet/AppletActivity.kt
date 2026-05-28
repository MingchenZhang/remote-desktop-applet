package me.zmc.remote_desktop_applet

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.http.SslError
import android.util.Log
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat

class AppletActivity : AppCompatActivity() {
    companion object {
        private const val HIDE_STATUS_BAR = true
        private const val USE_KEY_PRESS_JS_INJECTION = true
        const val EXTRA_APPLET_ID = "extra_applet_id"
        const val EXTRA_APPLET_URL = "extra_applet_url"
        const val EXTRA_APPLET_NAME = "extra_applet_name"
    }

    private lateinit var webView: WebView
    private var isAuthDialogShowing = false
    private var isSslDialogShowing = false
    private var lastPhysicalBackTime: Long = 0
    private var isInputFocused = false

    private val backPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            // This is only called if isEnabled = true (meaning we are NOT at root)
            if (webView.canGoBack()) {
                webView.goBack()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        // Pass null to super to prevent Android from restoring "hibernated" memory snapshots
        super.onCreate(null)

        setupFullscreenMode()

        val rootLayout = FrameLayout(this)
        webView = WebView(this)

        val appletId = intent.getStringExtra(EXTRA_APPLET_ID) ?: "default_applet"
        val targetUrl = intent.getStringExtra(EXTRA_APPLET_URL) ?: getString(R.string.target_url)
        val appletName = intent.getStringExtra(EXTRA_APPLET_NAME) ?: "Remote Desktop Applet"

        // Set the task description so the Recent Apps screen shows the applet name
        setTaskDescription(android.app.ActivityManager.TaskDescription(appletName))
        title = appletName

        val profileStore = ProfileStore.getInstance()
        val profile = profileStore.getOrCreateProfile(appletId)
        WebViewCompat.setProfile(webView, profile.name)

        // Clear history and cache on startup to ensure a fresh JS load
        webView.clearHistory()
        webView.clearCache(true)

        rootLayout.addView(webView)
        setContentView(rootLayout)

        // Handle back press gesture to perform standard Android back action
        onBackPressedDispatcher.addCallback(this, backPressedCallback)

        checkPermissions()

        // Ensure the layout respects system insets when the status bar is shown
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            if (!HIDE_STATUS_BAR) {
                v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            } else {
                v.setPadding(0, 0, 0, 0)
            }
            insets
        }

        // Enable cookies
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        // Bridge for clipboard and focus tracking
        webView.addJavascriptInterface(ClipboardInterface(), "AndroidClipboard")
        webView.addJavascriptInterface(FocusInterface(), "AndroidFocus")

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            allowFileAccess = true
            allowContentAccess = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            @Suppress("DEPRECATION")
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            
            // Set a desktop user agent to avoid being served mobile-crippled pages
            userAgentString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                Log.e("WebView", "Error: ${error?.description} (${error?.errorCode}) for URL: ${request?.url}")
                super.onReceivedError(view, request, error)
            }

            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                Log.e("WebView", "HTTP Error: ${errorResponse?.statusCode} for URL: ${request?.url}")
                super.onReceivedHttpError(view, request, errorResponse)
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                Log.w("WebView", "SSL Error: $error")
                
                if (isSslDialogShowing) {
                    handler?.cancel()
                    return
                }

                isSslDialogShowing = true
                val message = when (error?.primaryError) {
                    SslError.SSL_UNTRUSTED -> "The certificate authority is not trusted."
                    SslError.SSL_EXPIRED -> "The certificate has expired."
                    SslError.SSL_IDMISMATCH -> "The hostname does not match the certificate."
                    SslError.SSL_NOTYETVALID -> "The certificate is not yet valid."
                    else -> "SSL Certificate error."
                }

                AlertDialog.Builder(this@AppletActivity)
                    .setTitle("SSL Certificate Error")
                    .setMessage("$message\n\nDo you want to continue anyway?")
                    .setPositiveButton("Continue") { _, _ ->
                        isSslDialogShowing = false
                        handler?.proceed()
                    }
                    .setNegativeButton("Cancel") { _, _ ->
                        isSslDialogShowing = false
                        handler?.cancel()
                    }
                    .setOnCancelListener {
                        isSslDialogShowing = false
                        handler?.cancel()
                    }
                    .setCancelable(false)
                    .show()
            }

            override fun onReceivedHttpAuthRequest(view: WebView?, handler: HttpAuthHandler?, host: String?, realm: String?) {
                Log.i("WebView", "HTTP Auth Request from $host for $realm")
                
                // 1. Check if we already have credentials for this host/realm
                val credentials = view?.getHttpAuthUsernamePassword(host ?: "", realm ?: "")
                if (credentials != null && credentials.size == 2) {
                    handler?.proceed(credentials[0], credentials[1])
                    return
                }

                // 2. If no credentials, show dialog but only if one isn't already showing
                if (isAuthDialogShowing) {
                    handler?.cancel() 
                    return
                }

                isAuthDialogShowing = true
                val layout = LinearLayout(this@AppletActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(50, 20, 50, 20)
                }
                val usernameInput = EditText(this@AppletActivity).apply { hint = "Username" }
                val passwordInput = EditText(this@AppletActivity).apply { 
                    hint = "Password"
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
                layout.addView(usernameInput)
                layout.addView(passwordInput)

                AlertDialog.Builder(this@AppletActivity)
                    .setTitle("Authentication Required")
                    .setMessage("Enter credentials for $host")
                    .setView(layout)
                    .setPositiveButton("Login") { _, _ ->
                        isAuthDialogShowing = false
                        val user = usernameInput.text.toString()
                        val pass = passwordInput.text.toString()
                        // Save credentials for the session
                        view?.setHttpAuthUsernamePassword(host ?: "", realm ?: "", user, pass)
                        handler?.proceed(user, pass)
                    }
                    .setNegativeButton("Cancel") { _, _ ->
                        isAuthDialogShowing = false
                        handler?.cancel()
                    }
                    .setOnCancelListener {
                        isAuthDialogShowing = false
                        handler?.cancel()
                    }
                    .setCancelable(false)
                    .show()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectClipboardShim()
                updateBackCallbackState()
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                updateBackCallbackState()
            }
        }
        
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: android.view.View?, callback: CustomViewCallback?) {
                // Ignore fullscreen requests to avoid the 90-degree bug
                callback?.onCustomViewHidden()
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.grant(request.resources)
            }
        }

        webView.loadUrl(targetUrl)
    }

    private fun updateBackCallbackState() {
        // We only enable the interceptor if there is history.
        // If false, the OS is free to show the "Predictive Back" animation.
        backPressedCallback.isEnabled = webView.canGoBack()
    }

    override fun onDestroy() {
        // Aggressively tear down the WebView to prevent "hibernation"
        webView.run {
            stopLoading()
            onPause()
            clearHistory()
            clearCache(false) // Clears only volatile RAM cache
            loadUrl("about:blank") // Clear the current JS context
            removeAllViews()
            destroy()
        }
        super.onDestroy()
    }

    private fun setupFullscreenMode() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (HIDE_STATUS_BAR) {
            controller.hide(WindowInsetsCompat.Type.statusBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.statusBars())
        }
    }

    private fun simulateEscKeyPress() {
        val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE)
        val upEvent = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE)
        injectKey(downEvent)
        injectKey(upEvent)
    }

    private fun injectClipboardShim() {
        val script = """
            (function() {
                // 1. Mock the Clipboard API
                const shim = {
                    readText: () => Promise.resolve(window.AndroidClipboard.readText()),
                    writeText: (text) => {
                        window.AndroidClipboard.writeText(text);
                        return Promise.resolve();
                    }
                };
                try {
                    Object.defineProperty(navigator, 'clipboard', {
                        value: shim,
                        configurable: true
                    });
                } catch (e) { console.error('Clipboard shim failed', e); }

                // 2. Mock the Permissions API - crucial for apps that check before reading
                if (navigator.permissions && navigator.permissions.query) {
                    const originalQuery = navigator.permissions.query;
                    navigator.permissions.query = function(param) {
                        if (param && (param.name === 'clipboard-read' || param.name === 'clipboard-write')) {
                            return Promise.resolve({ state: 'granted', onchange: null });
                        }
                        return originalQuery.call(navigator.permissions, param);
                    };
                }

                // 3. Focus tracking
                const updateFocus = () => {
                    const active = document.activeElement;
                    const isInput = active && (
                        active.tagName === 'INPUT' || 
                        active.tagName === 'TEXTAREA' || 
                        active.isContentEditable
                    );
                    window.AndroidFocus.setFocused(!!isInput);
                };
                window.addEventListener('focusin', updateFocus);
                window.addEventListener('focusout', updateFocus);
                updateFocus();

                // 4. Trigger a visibility change event to force apps to re-sync
                document.dispatchEvent(new Event('visibilitychange'));
                window.dispatchEvent(new Event('focus'));
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            // When we regain focus, refresh the shim and notify the page
            injectClipboardShim()
        }
    }

    inner class ClipboardInterface {
        @JavascriptInterface
        fun readText(): String {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            return if (clip != null && clip.itemCount > 0) {
                clip.getItemAt(0).text?.toString() ?: ""
            } else {
                ""
            }
        }

        @JavascriptInterface
        fun writeText(text: String) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("remote-desktop-copy", text)
            clipboard.setPrimaryClip(clip)
        }
    }

    inner class FocusInterface {
        @JavascriptInterface
        fun setFocused(focused: Boolean) {
            isInputFocused = focused
        }
    }

    private fun checkPermissions() {
        val permissions = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA
        )
        val missingPermissions = permissions.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isNotEmpty()) {
            requestPermissions(missingPermissions.toTypedArray(), 1)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!USE_KEY_PRESS_JS_INJECTION) {
            return super.dispatchKeyEvent(event)
        }

        val keyCode = event.keyCode
        val isPhysical = isFromPhysicalKeyboard(event)
        
        // Detect if this is an ESC key or a BACK button from a physical keyboard
        val isEscape = keyCode == KeyEvent.KEYCODE_ESCAPE || 
                      event.scanCode == 1 ||
                      (keyCode == KeyEvent.KEYCODE_BACK && isPhysical)

        if (isEscape) {
            // MOMENTARY LOCK: 
            // We enable the callback instantly to tell the OS "I am handling this" 
            // This prevents the OS from triggering the Exit animation for this specific key press.
            backPressedCallback.isEnabled = true
            
            injectKey(event)

            // On Key Up, we restore the state (allow animations if we are at root)
            if (event.action == KeyEvent.ACTION_UP) {
                updateBackCallbackState()
            }
            return true
        }

        // List of other keys to intercept and bridge via JS injection
        // We prioritize keys that Android normally swallows or handles as system actions
        val isSystemKey = when (keyCode) {
            KeyEvent.KEYCODE_TAB,
            KeyEvent.KEYCODE_META_LEFT,
            KeyEvent.KEYCODE_META_RIGHT,
            KeyEvent.KEYCODE_CTRL_LEFT,
            KeyEvent.KEYCODE_CTRL_RIGHT,
            KeyEvent.KEYCODE_ALT_LEFT,
            KeyEvent.KEYCODE_ALT_RIGHT,
            KeyEvent.KEYCODE_F1, KeyEvent.KEYCODE_F2, KeyEvent.KEYCODE_F3,
            KeyEvent.KEYCODE_F4, KeyEvent.KEYCODE_F5, KeyEvent.KEYCODE_F6,
            KeyEvent.KEYCODE_F7, KeyEvent.KEYCODE_F8, KeyEvent.KEYCODE_F9,
            KeyEvent.KEYCODE_F10, KeyEvent.KEYCODE_F11, KeyEvent.KEYCODE_F12 -> true
            else -> false
        }

        // Context-aware routing:
        // If an input is focused, we only intercept system-critical keys.
        // This allows standard shortcuts like Ctrl+C/V to flow naturally to the WebView's native handler.
        if (isInputFocused) {
            if (isSystemKey) {
                injectKey(event)
                return true
            }
        } else {
            // If NOT in an input, we intercept system keys AND any chord with modifiers
            // to ensure high-fidelity delivery to the remote desktop environment.
            if (isSystemKey || event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) {
                injectKey(event)
                return true
            }
        }

        return super.dispatchKeyEvent(event)
    }

    private fun isFromPhysicalKeyboard(event: KeyEvent): Boolean {
        return event.deviceId > 0 && 
               event.device != null && 
               (event.device!!.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC || 
                (event.source and InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD)
    }

    private fun injectKey(event: KeyEvent) {
        val type = when (event.action) {
            KeyEvent.ACTION_DOWN -> "keydown"
            KeyEvent.ACTION_UP -> "keyup"
            else -> return
        }

        val keyCode = event.keyCode
        val jsKey = mapAndroidKeyToJsKey(keyCode)
        val jsCode = mapAndroidKeyToJsCode(keyCode)
        
        val ctrl = event.isCtrlPressed
        val alt = event.isAltPressed
        val shift = event.isShiftPressed
        val meta = event.isMetaPressed

        val script = """
            (function() {
                const event = new KeyboardEvent('$type', {
                    key: '$jsKey',
                    code: '$jsCode',
                    keyCode: $keyCode,
                    which: $keyCode,
                    ctrlKey: $ctrl,
                    altKey: $alt,
                    shiftKey: $shift,
                    metaKey: $meta,
                    bubbles: true,
                    cancelable: true,
                    repeat: ${event.repeatCount > 0}
                });
                
                // Dispatch to active element (bubbles up to document) or document directly
                const target = document.activeElement || document;
                target.dispatchEvent(event);
            })();
        """.trimIndent()

        webView.evaluateJavascript(script, null)
    }

    private fun mapAndroidKeyToJsKey(keyCode: Int): String {
        return when (keyCode) {
            KeyEvent.KEYCODE_ENTER -> "Enter"
            KeyEvent.KEYCODE_ESCAPE -> "Escape"
            KeyEvent.KEYCODE_BACK -> "Escape"
            KeyEvent.KEYCODE_TAB -> "Tab"
            KeyEvent.KEYCODE_DEL -> "Backspace"
            KeyEvent.KEYCODE_FORWARD_DEL -> "Delete"
            KeyEvent.KEYCODE_INSERT -> "Insert"
            KeyEvent.KEYCODE_MOVE_HOME -> "Home"
            KeyEvent.KEYCODE_MOVE_END -> "End"
            KeyEvent.KEYCODE_PAGE_UP -> "PageUp"
            KeyEvent.KEYCODE_PAGE_DOWN -> "PageDown"
            KeyEvent.KEYCODE_DPAD_UP -> "ArrowUp"
            KeyEvent.KEYCODE_DPAD_DOWN -> "ArrowDown"
            KeyEvent.KEYCODE_DPAD_LEFT -> "ArrowLeft"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "ArrowRight"
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> "Control"
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> "Alt"
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> "Shift"
            KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT -> "Meta"
            KeyEvent.KEYCODE_F1 -> "F1"
            KeyEvent.KEYCODE_F2 -> "F2"
            KeyEvent.KEYCODE_F3 -> "F3"
            KeyEvent.KEYCODE_F4 -> "F4"
            KeyEvent.KEYCODE_F5 -> "F5"
            KeyEvent.KEYCODE_F6 -> "F6"
            KeyEvent.KEYCODE_F7 -> "F7"
            KeyEvent.KEYCODE_F8 -> "F8"
            KeyEvent.KEYCODE_F9 -> "F9"
            KeyEvent.KEYCODE_F10 -> "F10"
            KeyEvent.KEYCODE_F11 -> "F11"
            KeyEvent.KEYCODE_F12 -> "F12"
            else -> {
                val char = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0).displayLabel
                if (char.code != 0) char.toString() else "Unidentified"
            }
        }
    }

    private fun mapAndroidKeyToJsCode(keyCode: Int): String {
        return when (keyCode) {
            KeyEvent.KEYCODE_ENTER -> "Enter"
            KeyEvent.KEYCODE_ESCAPE -> "Escape"
            KeyEvent.KEYCODE_BACK -> "Escape"
            KeyEvent.KEYCODE_TAB -> "Tab"
            KeyEvent.KEYCODE_DEL -> "Backspace"
            KeyEvent.KEYCODE_FORWARD_DEL -> "Delete"
            KeyEvent.KEYCODE_INSERT -> "Insert"
            KeyEvent.KEYCODE_MOVE_HOME -> "Home"
            KeyEvent.KEYCODE_MOVE_END -> "End"
            KeyEvent.KEYCODE_PAGE_UP -> "PageUp"
            KeyEvent.KEYCODE_PAGE_DOWN -> "PageDown"
            KeyEvent.KEYCODE_DPAD_UP -> "ArrowUp"
            KeyEvent.KEYCODE_DPAD_DOWN -> "ArrowDown"
            KeyEvent.KEYCODE_DPAD_LEFT -> "ArrowLeft"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "ArrowRight"
            KeyEvent.KEYCODE_CTRL_LEFT -> "ControlLeft"
            KeyEvent.KEYCODE_CTRL_RIGHT -> "ControlRight"
            KeyEvent.KEYCODE_ALT_LEFT -> "AltLeft"
            KeyEvent.KEYCODE_ALT_RIGHT -> "AltRight"
            KeyEvent.KEYCODE_SHIFT_LEFT -> "ShiftLeft"
            KeyEvent.KEYCODE_SHIFT_RIGHT -> "ShiftRight"
            KeyEvent.KEYCODE_META_LEFT -> "MetaLeft"
            KeyEvent.KEYCODE_META_RIGHT -> "MetaRight"
            else -> "Key" + KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
        }
    }
}
