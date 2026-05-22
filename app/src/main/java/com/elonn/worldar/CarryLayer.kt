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
import org.json.JSONArray
import org.json.JSONObject

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

class CarrySideRails(
    private val leftRail: View,
    private val leftHandle: TextView,
    private val leftItems: LinearLayout,
    private val rightRail: View,
    private val rightHandle: TextView,
    private val rightItems: LinearLayout
) {
    private var expandedSide: Side? = null
    private var leftContent = fallbackLeftContextRail()
    private var rightContent = fallbackRightContextRail()
    private var activeContext: CarrySurface? = null

    init {
        leftHandle.setOnClickListener { toggle(Side.LEFT) }
        rightHandle.setOnClickListener { toggle(Side.RIGHT) }
        collapse()
    }

    fun bind(left: ContextRailContent, right: ContextRailContent) {
        update(left, right)
    }

    fun update(left: ContextRailContent, right: ContextRailContent) {
        leftContent = left
        rightContent = right
        bindItems(leftItems, leftContent, activeContext = null)
        bindItems(rightItems, rightContent, activeContext)
        leftRail.visibility = View.VISIBLE
        rightRail.visibility = View.VISIBLE
        applyExpandedState()
    }

    fun focus(surface: CarrySurface) {
        activeContext = surface
        bindItems(leftItems, leftContent, activeContext = null)
        bindItems(rightItems, rightContent, activeContext)
    }

    fun handleBack(): Boolean {
        if (expandedSide == null) {
            return false
        }

        collapse()
        return true
    }

    fun collapse() {
        expandedSide = null
        applyExpandedState()
    }

    private fun toggle(side: Side) {
        expandedSide = if (expandedSide == side) null else side
        applyExpandedState()
    }

    private fun bindItems(
        container: LinearLayout,
        content: ContextRailContent,
        activeContext: CarrySurface?
    ) {
        container.removeAllViews()
        container.addView(headerFor(container, content))
        activeContext?.let { surface ->
            container.addView(
                rowFor(
                    container = container,
                    row = ContextRailRow("Open context", surface.title, "active_context"),
                    isLast = false,
                    emphasized = true
                )
            )
        }

        content.rows.forEachIndexed { index, row ->
            container.addView(
                rowFor(
                    container = container,
                    row = row,
                    isLast = index == content.rows.lastIndex,
                    emphasized = activeContext != null && rowMatchesSurface(row, activeContext)
                )
            )
        }
    }

    private fun headerFor(container: LinearLayout, content: ContextRailContent): LinearLayout =
        LinearLayout(container.context).apply {
            layoutParams = LinearLayout.LayoutParams(
                210.dp(container),
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 8.dp(container)
            }
            orientation = LinearLayout.VERTICAL
            setPadding(4.dp(container), 0, 4.dp(container), 0)

            addView(
                TextView(container.context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    text = content.title
                    setTextColor(Color.WHITE)
                    textSize = 12.0f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                }
            )
            addView(
                TextView(container.context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = 2.dp(container)
                    }
                    text = content.status
                    setTextColor(Color.parseColor("#B9CCC6"))
                    textSize = 10.0f
                }
            )
        }

    private fun rowFor(
        container: LinearLayout,
        row: ContextRailRow,
        isLast: Boolean,
        emphasized: Boolean
    ): LinearLayout =
        LinearLayout(container.context).apply {
            layoutParams = LinearLayout.LayoutParams(
                210.dp(container),
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                if (!isLast) {
                    bottomMargin = 6.dp(container)
                }
            }
            background = container.context.getDrawable(R.drawable.carry_dock_button_background)
            orientation = LinearLayout.VERTICAL
            minimumHeight = 42.dp(container)
            setPadding(10.dp(container), 6.dp(container), 10.dp(container), 6.dp(container))

            addView(
                TextView(container.context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    isSingleLine = true
                    text = row.title
                    setTextColor(if (emphasized) Color.parseColor("#D7FFF3") else colorFor(row.key))
                    textSize = 11.0f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                }
            )
            addView(
                TextView(container.context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = 1.dp(container)
                    }
                    maxLines = 3
                    text = row.detail
                    setTextColor(Color.parseColor("#C4D2CE"))
                    textSize = 10.0f
                }
            )
        }

    private fun applyExpandedState() {
        leftItems.visibility = if (expandedSide == Side.LEFT) View.VISIBLE else View.GONE
        rightItems.visibility = if (expandedSide == Side.RIGHT) View.VISIBLE else View.GONE
        leftHandle.text = if (expandedSide == Side.LEFT) "^" else "v"
        rightHandle.text = if (expandedSide == Side.RIGHT) "^" else "v"
    }

    private fun colorFor(key: String): Int =
        when {
            key.contains("find", ignoreCase = true) -> Color.parseColor("#FFF4D7")
            key.contains("social", ignoreCase = true) -> Color.parseColor("#E5F6FF")
            key.contains("event", ignoreCase = true) -> Color.parseColor("#FFE6F0")
            key.contains("calendar", ignoreCase = true) -> Color.parseColor("#EEFFE7")
            key.contains("message", ignoreCase = true) -> Color.parseColor("#F5E8FF")
            key.contains("member", ignoreCase = true) -> Color.parseColor("#D7FFF3")
            key.contains("contract", ignoreCase = true) -> Color.parseColor("#FFFFFF")
            else -> Color.parseColor("#E7F4F8")
        }

    private fun rowMatchesSurface(row: ContextRailRow, surface: CarrySurface): Boolean {
        val key = "${row.key} ${row.title}".lowercase()
        val surfaceKey = "${surface.key} ${surface.title}".lowercase()
        return key.split(Regex("[^a-z0-9]+"))
            .filter { it.length > 3 }
            .any { token -> surfaceKey.contains(token) }
    }

    private fun Int.dp(view: View): Int =
        (this * view.resources.displayMetrics.density).toInt()

    private enum class Side {
        LEFT,
        RIGHT
    }
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
    private val authTokenProvider: () -> String? = { null },
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
                authToken = authTokenProvider(),
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
    private val authToken: String?,
    private val host: WebPanelHost
) : CarryAppPanel {
    private var container: FrameLayout? = null
    private var statusView: TextView? = null
    private var webView: WebView? = null
    private var renderedPanelDocument = false

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
        renderedPanelDocument = false
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
            setBackgroundColor(Color.parseColor("#08110D"))
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    CookieManager.getInstance().flush()
                    if (renderedPanelDocument) {
                        statusView?.visibility = View.GONE
                        return
                    }

                    view?.evaluateJavascript(
                        "(function(){return document.body ? document.body.innerText : '';})()"
                    ) { encodedBody ->
                        val currentView = webView ?: return@evaluateJavascript
                        if (currentView != view) {
                            return@evaluateJavascript
                        }

                        val bodyText = encodedBody?.decodeJavascriptString().orEmpty()
                        val trimmed = bodyText.trimStart()
                        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                            renderedPanelDocument = true
                            currentView.loadDataWithBaseURL(
                                this@WebCarryPanel.url,
                                panelDocument(this@WebCarryPanel.title, renderJsonPanel(bodyText, 200)),
                                "text/html",
                                "UTF-8",
                                null
                            )
                        } else {
                            statusView?.visibility = View.GONE
                        }
                    }
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
            seedAuthCookies(this@WebCarryPanel.authToken)
            showStatus("Loading $title...")
            loadUrl(this@WebCarryPanel.url)
        }

    private fun renderJsonPanel(body: String, status: Int): String {
        val value = runCatching {
            if (body.trimStart().startsWith("[")) {
                JSONArray(body)
            } else {
                JSONObject(body)
            }
        }.getOrElse {
            return "<pre>${body.escapeHtml()}</pre>"
        }

        return when (value) {
            is JSONObject -> renderObjectPanel(value, status)
            is JSONArray -> section("Items", renderArray(value))
            else -> "<pre>${body.escapeHtml()}</pre>"
        }
    }

    private fun renderObjectPanel(payload: JSONObject, status: Int): String {
        htmlFragment(payload)?.let { return it }

        val panelTitle = payload.bestString("title", "name", "kind").ifBlank { title }
        val summary = payload.bestString("summary", "description", "message", "error")
        val body = StringBuilder()

        body.append("<header class=\"panel-header\"><p>")
            .append(payload.optString("kind", "World").escapeHtml())
            .append("</p><h1>")
            .append(panelTitle.escapeHtml())
            .append("</h1>")
        if (summary.isNotBlank()) {
            body.append("<span>").append(summary.escapeHtml()).append("</span>")
        }
        if (status !in 200..299) {
            body.append("<strong>HTTP ").append(status).append("</strong>")
        }
        body.append("</header>")

        renderSelectedObject(payload)?.let { body.append(it) }

        payload.optJSONObject("objects")?.let { objects ->
            objects.keys().forEachRemaining { key ->
                val items = objects.optJSONArray(key) ?: return@forEachRemaining
                body.append(section(key.toDisplayTitle(), renderArray(items)))
            }
        }

        listOf("threads", "messages", "members", "calendars", "events", "findings").forEach { key ->
            payload.optJSONArray(key)?.let { items ->
                body.append(section(key.toDisplayTitle(), renderArray(items)))
            }
        }

        payload.optJSONObject("counts")?.let { counts ->
            body.append(section("Counts", renderKeyValues(counts)))
        }

        payload.optJSONObject("actions")?.let { actions ->
            body.append(section("Actions", renderKeyValues(actions)))
        }

        if (body.isBlank()) {
            body.append("<pre>").append(payload.toString(2).escapeHtml()).append("</pre>")
        }

        return body.toString()
    }

    private fun renderSelectedObject(payload: JSONObject): String? {
        val selected = payload.optJSONObject("selected_object")
            ?: payload.optJSONObject("selected_thread")
            ?: return null
        return section("Selected", renderObjectCard(selected))
    }

    private fun renderArray(items: JSONArray): String {
        if (items.length() == 0) {
            return "<p class=\"empty\">No items yet.</p>"
        }

        return buildString {
            append("<div class=\"list\">")
            for (index in 0 until items.length()) {
                val item = items.opt(index)
                append(
                    when (item) {
                        is JSONObject -> renderObjectCard(item)
                        is JSONArray -> "<pre>${item.toString(2).escapeHtml()}</pre>"
                        else -> "<article class=\"item\"><h2>${item.toString().escapeHtml()}</h2></article>"
                    }
                )
            }
            append("</div>")
        }
    }

    private fun renderObjectCard(item: JSONObject): String {
        val itemTitle = item.bestString("title", "name", "label", "email", "id").ifBlank { "Item" }
        val summary = item.bestString("summary", "body", "description", "status", "visibility", "starts_at", "updated_at")
        val metadata = listOf("kind", "view", "message_count", "member_count", "participant_count", "timezone", "created_at")
            .mapNotNull { key -> item.optString(key).takeIf { it.isNotBlank() }?.let { key.toDisplayTitle() to it } }

        return buildString {
            append("<article class=\"item\"><h2>").append(itemTitle.escapeHtml()).append("</h2>")
            if (summary.isNotBlank() && summary != itemTitle) {
                append("<p>").append(summary.escapeHtml()).append("</p>")
            }
            if (metadata.isNotEmpty()) {
                append("<dl>")
                metadata.forEach { (key, value) ->
                    append("<dt>").append(key.escapeHtml()).append("</dt><dd>").append(value.escapeHtml()).append("</dd>")
                }
                append("</dl>")
            }
            append("</article>")
        }
    }

    private fun renderKeyValues(values: JSONObject): String =
        buildString {
            append("<dl class=\"kv\">")
            values.keys().forEachRemaining { key ->
                val value = values.opt(key)
                append("<dt>").append(key.toDisplayTitle().escapeHtml()).append("</dt><dd>")
                append((value?.toString() ?: "").escapeHtml())
                append("</dd>")
            }
            append("</dl>")
        }

    private fun htmlFragment(payload: JSONObject): String? {
        listOf("html", "body", "content", "panel", "fragment").forEach { key ->
            val value = payload.optString(key)
            if (value.contains("<") && value.contains(">")) {
                return value
            }
        }

        payload.optJSONObject("payload")?.let { nested ->
            return htmlFragment(nested)
        }

        return null
    }

    private fun section(title: String, body: String): String =
        "<section><h1>${title.escapeHtml()}</h1>$body</section>"

    private fun panelDocument(pageTitle: String, body: String): String =
        """
        <!doctype html>
        <html>
        <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>
                :root { color-scheme: dark; background: #08110d; color: #e8f3ed; font-family: system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; }
                * { box-sizing: border-box; }
                body { margin: 0; padding: 14px; background: #08110d; }
                .panel-header { border-bottom: 1px solid rgba(220, 245, 230, 0.18); margin-bottom: 14px; padding-bottom: 12px; }
                .panel-header p { color: #9fb4aa; font-size: 12px; letter-spacing: .08em; margin: 0 0 4px; text-transform: uppercase; }
                .panel-header h1, section > h1 { font-size: 18px; line-height: 1.2; margin: 0 0 8px; }
                .panel-header span, .empty { color: #b7c8bf; }
                section { margin: 0 0 18px; }
                .list { display: grid; gap: 10px; }
                .item { background: #101c17; border: 1px solid rgba(220, 245, 230, 0.14); border-radius: 8px; padding: 12px; }
                .item h2 { font-size: 15px; line-height: 1.25; margin: 0 0 6px; }
                .item p { color: #c9d8d1; font-size: 13px; line-height: 1.4; margin: 0; overflow-wrap: anywhere; }
                dl { display: grid; grid-template-columns: minmax(86px, 34%) 1fr; gap: 4px 10px; margin: 10px 0 0; }
                dt { color: #91a89c; font-size: 11px; text-transform: uppercase; }
                dd { color: #d9e7e0; font-size: 12px; margin: 0; overflow-wrap: anywhere; }
                .kv { background: #101c17; border-radius: 8px; padding: 12px; }
                pre { white-space: pre-wrap; overflow-wrap: anywhere; background: #101c17; border-radius: 8px; margin: 0; padding: 12px; }
                a { color: #9ed7ff; }
                button, input, textarea, select { font: inherit; max-width: 100%; }
            </style>
            <title>${pageTitle.escapeHtml()}</title>
        </head>
        <body>$body</body>
        </html>
        """.trimIndent()

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

    private fun seedAuthCookies(token: String?) {
        if (token.isNullOrBlank()) {
            return
        }

        CookieManager.getInstance().apply {
            listOf(
                "https://elonn.com",
                "https://world.elonn.com",
                "https://api.elonn.com",
                "https://social.elonn.com",
                "https://time.elonn.com",
                "https://find.elonn.com"
            ).forEach { baseUrl ->
                setCookie(baseUrl, "elonn_api_token=$token; Path=/; Secure; SameSite=Lax")
            }
            flush()
        }
    }

    private fun showStatus(message: String) {
        statusView?.text = message
        statusView?.visibility = View.VISIBLE
    }

    private fun JSONObject.bestString(vararg keys: String): String {
        keys.forEach { key ->
            val value = optString(key)
            if (value.isNotBlank() && value != "null") {
                return value
            }
        }
        return ""
    }

    private fun String.toDisplayTitle(): String =
        split('_', '-', ' ')
            .filter { it.isNotBlank() }
            .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }

    private fun String.escapeHtml(): String =
        replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")

    private fun String.decodeJavascriptString(): String =
        runCatching { JSONArray("[$this]").optString(0) }.getOrDefault("")
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
