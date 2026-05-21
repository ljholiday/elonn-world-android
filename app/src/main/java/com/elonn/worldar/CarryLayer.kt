package com.elonn.worldar

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

data class CarrySurface(
    val key: String,
    val title: String,
    val panelText: String,
    val runtimePanelUrl: String?
)

class CarryAppDock(
    private val root: View,
    private val onSelected: (CarrySurface) -> Unit
) {
    private var surfaces: List<CarrySurface> = emptyList()

    fun bind(initialSurfaces: List<CarrySurface>) {
        update(initialSurfaces)
    }

    fun update(nextSurfaces: List<CarrySurface>) {
        surfaces = nextSurfaces
        val container = root as? ViewGroup ?: return
        container.removeAllViews()

        surfaces.forEachIndexed { index, surface ->
            container.addView(buttonFor(surface, index == surfaces.lastIndex))
        }
    }

    private fun buttonFor(surface: CarrySurface, isLast: Boolean): TextView =
        TextView(root.context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                if (!isLast) {
                    marginEnd = 6.dp()
                }
            }
            background = root.context.getDrawable(R.drawable.carry_dock_button_background)
            gravity = Gravity.CENTER
            minWidth = 72.dp()
            minHeight = 38.dp()
            setPadding(12.dp(), 4.dp(), 12.dp(), 4.dp())
            isSingleLine = true
            text = surface.title
            setTextColor(colorFor(surface.key))
            textSize = 11.0f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setOnClickListener { onSelected(surface) }
        }

    private fun colorFor(key: String): Int =
        when {
            key.contains("find", ignoreCase = true) -> Color.parseColor("#FFF4D7")
            key.contains("social", ignoreCase = true) -> Color.parseColor("#E5F6FF")
            key.contains("event", ignoreCase = true) -> Color.parseColor("#FFE6F0")
            key.contains("calendar", ignoreCase = true) -> Color.parseColor("#EEFFE7")
            key.contains("message", ignoreCase = true) -> Color.parseColor("#F5E8FF")
            else -> Color.parseColor("#E7F4F8")
        }

    private fun Int.dp(): Int =
        (this * root.resources.displayMetrics.density).toInt()
}

class CarryActiveWindow(
    private val root: View,
    private val titleView: TextView,
    private val contentContainer: FrameLayout,
    closeControl: View,
    private val panelHost: CarryPanelHost
) {
    private var activeSurfaceKey: String? = null
    private var activePanel: CarryAppPanel? = null

    init {
        closeControl.setOnClickListener {
            close()
        }
    }

    fun show(surface: CarrySurface) {
        if (activeSurfaceKey == surface.key && root.visibility == View.VISIBLE) {
            return
        }

        val panel = panelHost.panelFor(surface)
        clearContent()
        activeSurfaceKey = surface.key
        activePanel = panel
        titleView.text = panel.title
        contentContainer.addView(panel.createView(contentContainer.context))
        root.visibility = View.VISIBLE
    }

    fun close() {
        activeSurfaceKey = null
        clearContent()
        root.visibility = View.INVISIBLE
    }

    fun handleBack(): Boolean {
        if (root.visibility != View.VISIBLE) {
            return false
        }

        if (activePanel?.handleBack() == true) {
            return true
        }

        close()
        return true
    }

    private fun clearContent() {
        activePanel?.onRemovedFromWindow()
        activePanel = null
        contentContainer.removeAllViews()
    }
}

class CarryPanelHost(
    private val fileChooserHost: WebPanelHost = NoOpWebPanelHost,
    private val worldBaseUrl: String = WORLD_BASE_URL
) {
    fun panelFor(surface: CarrySurface): CarryAppPanel {
        val runtimeUrl = surface.runtimePanelUrl
        return if (runtimeUrl.isNullOrBlank()) {
            PlaceholderCarryPanel(surface.title, surface.panelText)
        } else {
            WebCarryPanel(
                title = surface.title,
                url = worldUrl(runtimeUrl),
                host = fileChooserHost
            )
        }
    }

    private fun worldUrl(path: String): String {
        val absolute = if (path.startsWith("http://") || path.startsWith("https://")) {
            path
        } else {
            worldBaseUrl.trimEnd('/') + "/" + path.trimStart('/')
        }

        val separator = if (absolute.contains('?')) "&" else "?"
        return absolute + separator + "surface=android_carry"
    }
}

interface CarryAppPanel {
    val title: String

    fun createView(context: Context): View

    fun handleBack(): Boolean = false

    fun onRemovedFromWindow() = Unit
}

interface WebPanelHost {
    fun openFileChooser(
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: WebChromeClient.FileChooserParams
    ): Boolean
}

object NoOpWebPanelHost : WebPanelHost {
    override fun openFileChooser(
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: WebChromeClient.FileChooserParams
    ): Boolean {
        filePathCallback.onReceiveValue(null)
        return true
    }
}

class WebCarryPanel(
    override val title: String,
    private val url: String,
    private val host: WebPanelHost
) : CarryAppPanel {
    private var container: FrameLayout? = null
    private var statusView: TextView? = null
    private var webView: WebView? = null

    override fun createView(context: Context): View =
        existingOrNewContainer(context).also { view ->
            (view.parent as? ViewGroup)?.removeView(view)
        }

    override fun handleBack(): Boolean {
        val view = webView ?: return false
        if (!view.canGoBack()) {
            return false
        }

        view.goBack()
        return true
    }

    override fun onRemovedFromWindow() {
        CookieManager.getInstance().flush()
        webView?.destroy()
        webView = null
        statusView = null
        container = null
    }

    private fun existingOrNewContainer(context: Context): FrameLayout =
        container ?: FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

            val panelWebView = createWebView(context)
            val panelStatusView = createStatusView(context)
            addView(panelWebView)
            addView(panelStatusView)
            container = this
            webView = panelWebView
            statusView = panelStatusView
        }

    private fun createWebView(context: Context): WebView =
        WebView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

            configureCookies(this)
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    CookieManager.getInstance().flush()
                    statusView?.visibility = View.GONE
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    if (request?.isForMainFrame == true) {
                        showStatus("$title could not load. Check connection and try again.")
                    }
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    if (newProgress < 100) {
                        showStatus("Loading $title...")
                    }
                }

                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>,
                    fileChooserParams: FileChooserParams
                ): Boolean = host.openFileChooser(filePathCallback, fileChooserParams)
            }
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.loadsImagesAutomatically = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.userAgentString = settings.userAgentString + " ElonnWorldAndroid/0.1 CarrySurface/1"
            settings.layoutAlgorithm = WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING
            isVerticalScrollBarEnabled = true
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            showStatus("Loading $title...")
            loadUrl(this@WebCarryPanel.url)
        }

    private fun createStatusView(context: Context): TextView =
        TextView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
            text = "Loading $title..."
            setTextColor(Color.parseColor("#D0DDD5"))
            textSize = 14.0f
            visibility = View.VISIBLE
        }

    private fun configureCookies(webView: WebView) {
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
    }

    private fun showStatus(message: String) {
        statusView?.text = message
        statusView?.visibility = View.VISIBLE
    }
}

class PlaceholderCarryPanel(
    override val title: String,
    private val placeholderText: String
) : CarryAppPanel {
    override fun createView(context: Context): View =
        TextView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            text = placeholderText
            setTextColor(Color.parseColor("#D0DDD5"))
            textSize = 16.0f
        }
}

const val WORLD_BASE_URL = "https://world.elonn.com"
