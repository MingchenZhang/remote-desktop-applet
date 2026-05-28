package me.zmc.remote_desktop_applet

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.*
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.UUID

class ManagerActivity : AppCompatActivity() {

    private var loadingDialog: AlertDialog? = null
    private var headlessWebView: WebView? = null
    private val handler = Handler(Looper.getMainLooper())
    
    private var timeoutRemainingMs = 10000L
    private var lastStartTime = 0L
    private var isTimeoutPaused = false
    private var isFinishing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_manager)

        val rootLayout = findViewById<View>(R.id.root_layout)
        val nameInput = findViewById<EditText>(R.id.name_input)
        val urlInput = findViewById<EditText>(R.id.url_input)
        val launchBtn = findViewById<android.widget.Button>(R.id.launch_button)
        val createBtn = findViewById<android.widget.Button>(R.id.create_button)

        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                v.paddingLeft,
                systemBars.top,
                v.paddingRight,
                systemBars.bottom
            )
            insets
        }

        launchBtn.setOnClickListener {
            val name = nameInput.text.toString().takeIf { it.isNotBlank() } ?: "Unnamed Applet"
            val url = urlInput.text.toString()

            if (url.isBlank()) {
                Toast.makeText(this, "Please enter a URL", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            launchAppletDirectly(name, url)
        }

        createBtn.setOnClickListener {
            val name = nameInput.text.toString()
            val url = urlInput.text.toString()

            if (name.isBlank() || url.isBlank()) {
                Toast.makeText(this, "Please enter both name and URL", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            startFaviconCollection(name, url)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun startFaviconCollection(name: String, url: String) {
        isFinishing = false
        // 1. Show Material Design 3 Loading Dialog
        val progressBar = ProgressBar(this).apply {
            isIndeterminate = true
            setPadding(0, 40, 0, 40)
        }
        
        loadingDialog = MaterialAlertDialogBuilder(this)
            .setTitle("Collecting Favicon")
            .setMessage("Please wait while we fetch the icon for $url...")
            .setView(progressBar)
            .setCancelable(false)
            .show()

        // 2. Setup Headless WebView
        headlessWebView = WebView(this).apply {
            settings.javaScriptEnabled = true
            
            webViewClient = object : WebViewClient() {
                override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                    handler?.proceed() // Silent proceed for homelabs
                }

                override fun onReceivedHttpAuthRequest(view: WebView?, handler: HttpAuthHandler?, host: String?, realm: String?) {
                    pauseTimeout()
                    showAuthDialog(handler, host, realm)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onReceivedIcon(view: WebView?, icon: Bitmap?) {
                    if (icon != null && !isFinishing) {
                        finishShortcutCreation(icon, name, url)
                    }
                }
            }
        }

        // 3. Start Timeout & Load URL
        timeoutRemainingMs = 10000L
        startTimeout(name, url)
        headlessWebView?.loadUrl(url)
    }

    private fun startTimeout(name: String, url: String) {
        lastStartTime = System.currentTimeMillis()
        isTimeoutPaused = false
        handler.postDelayed({
            if (!isTimeoutPaused && !isFinishing) {
                finishShortcutCreation(null, name, url)
            }
        }, timeoutRemainingMs)
    }

    private fun pauseTimeout() {
        if (isTimeoutPaused || isFinishing) return
        val elapsed = System.currentTimeMillis() - lastStartTime
        timeoutRemainingMs -= elapsed
        if (timeoutRemainingMs < 0) timeoutRemainingMs = 0
        handler.removeCallbacksAndMessages(null)
        isTimeoutPaused = true
        loadingDialog?.hide()
    }

    private fun resumeTimeout(name: String, url: String) {
        if (!isTimeoutPaused || isFinishing) return
        loadingDialog?.show()
        startTimeout(name, url)
    }

    private fun showAuthDialog(handler: HttpAuthHandler?, host: String?, realm: String?) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 20, 60, 20)
        }
        val usernameInput = EditText(this).apply { hint = "Username" }
        val passwordInput = EditText(this).apply { 
            hint = "Password"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        layout.addView(usernameInput)
        layout.addView(passwordInput)

        val name = findViewById<EditText>(R.id.name_input).text.toString()
        val url = findViewById<EditText>(R.id.url_input).text.toString()

        MaterialAlertDialogBuilder(this)
            .setTitle("Authentication Required")
            .setMessage("Enter credentials for $host")
            .setView(layout)
            .setPositiveButton("Login") { _, _ ->
                handler?.proceed(usernameInput.text.toString(), passwordInput.text.toString())
                resumeTimeout(name, url)
            }
            .setNegativeButton("Cancel") { _, _ ->
                handler?.cancel()
                resumeTimeout(name, url)
            }
            .setCancelable(false)
            .show()
    }

    private fun finishShortcutCreation(icon: Bitmap?, name: String, url: String) {
        if (isFinishing) return
        isFinishing = true

        handler.removeCallbacksAndMessages(null)
        loadingDialog?.dismiss()
        loadingDialog = null
        
        headlessWebView?.run {
            stopLoading()
            destroy()
        }
        headlessWebView = null

        createShortcut(name, url, icon)
    }

    private fun createShortcut(name: String, url: String, icon: Bitmap?) {
        val appletId = UUID.randomUUID().toString()

        val intent = Intent(this, AppletActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = android.net.Uri.parse("applet://$appletId")
            putExtra(AppletActivity.EXTRA_APPLET_ID, appletId)
            putExtra(AppletActivity.EXTRA_APPLET_URL, url)
            putExtra(AppletActivity.EXTRA_APPLET_NAME, name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
        }

        val shortcutIcon = if (icon != null) {
            IconCompat.createWithBitmap(icon)
        } else {
            IconCompat.createWithResource(this, R.mipmap.ic_launcher)
        }

        val shortcut = ShortcutInfoCompat.Builder(this, appletId)
            .setShortLabel(name)
            .setLongLabel(name)
            .setIcon(shortcutIcon)
            .setIntent(intent)
            .build()

        if (ShortcutManagerCompat.isRequestPinShortcutSupported(this)) {
            ShortcutManagerCompat.requestPinShortcut(this, shortcut, null)
            Toast.makeText(this, "Shortcut requested for $name", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Pinning shortcuts not supported on this launcher", Toast.LENGTH_LONG).show()
        }
    }

    private fun launchAppletDirectly(name: String, url: String) {
        val appletId = UUID.randomUUID().toString()

        val intent = Intent(this, AppletActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = android.net.Uri.parse("applet://$appletId")
            putExtra(AppletActivity.EXTRA_APPLET_ID, appletId)
            putExtra(AppletActivity.EXTRA_APPLET_URL, url)
            putExtra(AppletActivity.EXTRA_APPLET_NAME, name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
        }

        startActivity(intent)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        headlessWebView?.destroy()
        loadingDialog?.dismiss()
        super.onDestroy()
    }
}
