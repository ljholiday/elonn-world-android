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
    val runtimePanelUrl: String?,
    val surfaceId: String = key
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
    private val rightItems: LinearLayout,
    private val onRowSelected: (ContextRailRow) -> Unit = {}
) {
    private var leftContent = fallbackLeftContextRail()
    private var rightContent = fallbackRightContextRail()
    private var activeContext: CarrySurface? = null

    init {
        applyExpandedState()
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
        return false
    }

    fun collapse() {
        applyExpandedState()
    }

    private fun bindItems(
        container: LinearLayout,
        content: ContextRailContent,
        activeContext: CarrySurface?
    ) {
        container.removeAllViews()
        container.addView(headerFor(container, content))
        val focusedRows = focusedRowsFor(content, activeContext)
        activeContext?.takeUnless { isMessagesContext(it) && content == rightContent }?.let { surface ->
            container.addView(
                rowFor(
                    container = container,
                    row = ContextRailRow("Open context", surface.title, "active_context"),
                    isLast = false,
                    emphasized = true,
                    interactive = false
                )
            )
        }

        focusedRows.forEachIndexed { index, row ->
            container.addView(
                rowFor(
                    container = container,
                    row = row,
                    isLast = index == focusedRows.lastIndex,
                    emphasized = activeContext != null && rowMatchesSurface(row, activeContext),
                    interactive = content == rightContent
                )
            )
        }
    }

    private fun focusedRowsFor(
        content: ContextRailContent,
        activeContext: CarrySurface?
    ): List<ContextRailRow> {
        if (content != rightContent || activeContext == null || !isMessagesContext(activeContext)) {
            return content.rows
        }

        val threadRows = content.rows.filter { it.key.startsWith("message_thread:", ignoreCase = true) }
        return buildList {
            add(ContextRailRow("Messages inbox", "View all direct message threads.", "messages"))
            add(ContextRailRow("New message", "Start a direct message thread.", "messages_new"))
            addAll(threadRows)
        }
    }

    private fun isMessagesContext(surface: CarrySurface): Boolean =
        surface.key.contains("message", ignoreCase = true) ||
            surface.title.contains("message", ignoreCase = true)

    private fun headerFor(container: LinearLayout, content: ContextRailContent): LinearLayout =
        LinearLayout(container.context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
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
        emphasized: Boolean,
        interactive: Boolean
    ): LinearLayout =
        LinearLayout(container.context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
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
            if (interactive) {
                isClickable = true
                isFocusable = true
                setOnClickListener { onRowSelected(row) }
            }

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
        leftItems.visibility = View.VISIBLE
        rightItems.visibility = View.VISIBLE
        leftHandle.text = leftContent.title
        rightHandle.text = rightContent.title
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
                url = worldUrl(runtimeUrl, surface.surfaceId),
                authToken = authTokenProvider(),
                host = fileChooserHost
            )
        }
    }

    private fun worldUrl(path: String, surfaceId: String): String {
        val absolute = if (path.startsWith("http://") || path.startsWith("https://")) {
            path
        } else {
            worldBaseUrl.trimEnd('/') + "/" + path.trimStart('/')
        }

        val separator = if (absolute.contains('?')) "&" else "?"
        return absolute + separator + "surface=android_carry&runtime=android&surface_id=" + Uri.encode(surfaceId)
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

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    renderedPanelDocument = false
                    return false
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
        renderMessagesPanel(payload)?.let { return it }

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

    private fun renderMessagesPanel(payload: JSONObject): String? {
        if (payload.optString("view") != "messages" && !payload.has("threads")) {
            return null
        }

        val threads = payload.optJSONArray("threads") ?: JSONArray()
        val selectedThread = payload.optJSONObject("selected_thread")
        return if (selectedThread != null) {
            renderMessageThreadPanel(selectedThread, payload.optJSONArray("messages") ?: JSONArray())
        } else {
            renderMessageInboxPanel(threads)
        }
    }

    private fun renderMessageInboxPanel(threads: JSONArray): String =
        buildString {
            append("<header class=\"panel-header\"><p>World Messages</p><h1>Messages</h1>")
            append("<span>").append(threads.length()).append(" message thread")
            append(if (threads.length() == 1) "." else "s.")
            append("</span></header>")

            append("<section><h1>New Message</h1>")
            append("<form class=\"message-form\" data-create-thread action=\"/world/messages\" method=\"post\">")
            append("<label>To<input name=\"recipient\" required maxlength=\"160\" autocomplete=\"off\" placeholder=\"member id, username, or display name\"></label>")
            append("<label>Message<textarea name=\"body\" required maxlength=\"500\" placeholder=\"Write a message\"></textarea></label>")
            append("<button type=\"submit\">Send message</button><p class=\"form-status\" data-form-status></p>")
            append("</form></section>")

            append("<section><h1>Inbox</h1>")
            if (threads.length() == 0) {
                append("<p class=\"empty\">No message threads yet.</p>")
            } else {
                append("<div class=\"list\">")
                for (index in 0 until threads.length()) {
                    val thread = threads.optJSONObject(index) ?: continue
                    append(renderMessageThreadCard(thread))
                }
                append("</div>")
            }
            append("</section>")
            append(messagesPanelScript())
        }

    private fun renderMessageThreadPanel(thread: JSONObject, messages: JSONArray): String =
        buildString {
        val threadTitle = thread.bestString("title", "participant_label", "summary").ifBlank { "Direct messages" }
        val threadSummary = thread.bestString("participant_label", "summary").ifBlank { "Message thread" }
        val threadId = thread.optString("id")
        val messageEndpoint = thread.linkOrAction("messages", "send_message")
            .ifBlank { if (threadId.isBlank()) "" else "/world/messages/${threadId.escapeHtml()}/messages" }

            append("<header class=\"panel-header\"><p>World Messages</p><h1>")
            append(threadTitle.escapeHtml())
            append("</h1><span>")
            append(threadSummary.escapeHtml())
            append("</span></header>")

            append("<div class=\"actions\"><a href=\"/world/panels/messages?surface=android_carry\">Back to inbox</a></div>")
            append("<section><h1>Thread</h1>")
            if (messages.length() == 0) {
                append("<p class=\"empty\">No messages yet.</p>")
            } else {
                append("<ul class=\"messages\">")
                for (index in 0 until messages.length()) {
                    val message = messages.optJSONObject(index) ?: continue
                    append(renderDirectMessage(message))
                }
                append("</ul>")
            }
            append("</section>")

            append("<section><h1>Reply</h1>")
            append("<form class=\"message-form\" data-reply-thread action=\"")
            append(messageEndpoint.escapeHtml())
            append("\" method=\"post\">")
            append("<label>Message<textarea name=\"body\" required maxlength=\"500\" placeholder=\"Write a message\"></textarea></label>")
            append("<button type=\"submit\">Send message</button><p class=\"form-status\" data-form-status></p>")
            append("</form></section>")
            append(messagesPanelScript())
        }

    private fun renderMessageThreadCard(thread: JSONObject): String {
        val threadId = thread.optString("id")
        val threadTitle = thread.bestString("title", "participant_label", "summary").ifBlank { "Message thread" }
        val summary = thread.bestString("summary", "participant_label", "body").ifBlank { "Message thread" }
        val unread = thread.optInt("unread_count", 0)
        val href = "/world/panels/messages?thread=${threadId.escapeHtml()}&surface=android_carry"

        return buildString {
            append("<article class=\"item message-thread\"><a href=\"")
            append(href)
            append("\"><h2>")
            append(threadTitle.escapeHtml())
            append("</h2><p>")
            append(summary.escapeHtml())
            append("</p>")
            if (unread > 0) {
                append("<strong>").append(unread).append(" unread</strong>")
            }
            append("</a></article>")
        }
    }

    private fun renderDirectMessage(message: JSONObject): String {
        val author = message.bestString("author_label", "author_identity_user_id", "identity_user_id", "sender").ifBlank { "Member" }
        val body = message.bestString("body", "message", "summary")
        val created = message.bestString("created_at", "sent_at")
        return buildString {
            append("<li><strong>").append(author.escapeHtml()).append("</strong>")
            if (created.isNotBlank()) {
                append("<span>").append(created.escapeHtml()).append("</span>")
            }
            append("<p>").append(body.escapeHtml()).append("</p></li>")
        }
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

    private fun JSONObject.linkOrAction(vararg keys: String): String {
        val links = optJSONObject("links")
        val actions = optJSONObject("actions")
        keys.forEach { key ->
            links?.optString(key)?.takeIf { it.isNotBlank() }?.let { return it }
            actions?.optString(key)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return ""
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
                .item a { color: inherit; display: block; text-decoration: none; }
                .item h2 { font-size: 15px; line-height: 1.25; margin: 0 0 6px; }
                .item p { color: #c9d8d1; font-size: 13px; line-height: 1.4; margin: 0; overflow-wrap: anywhere; }
                .actions { display: flex; flex-wrap: wrap; gap: 8px; margin: 0 0 14px; }
                .actions a, button { min-height: 32px; border: 1px solid rgba(215, 255, 243, 0.28); border-radius: 8px; background: #d7fff3; color: #10201d; font-weight: 800; padding: 7px 10px; text-decoration: none; }
                .message-form { display: grid; gap: 10px; }
                .message-form label { color: #b7c8bf; display: grid; gap: 5px; font-size: 12px; font-weight: 700; text-transform: uppercase; }
                input, textarea { width: 100%; border: 1px solid rgba(215, 255, 243, 0.22); border-radius: 8px; background: #09120f; color: #ffffff; padding: 9px 10px; }
                textarea { min-height: 86px; resize: vertical; }
                .form-status { color: #b7c8bf; min-height: 18px; margin: 0; }
                .messages { display: grid; gap: 8px; list-style: none; margin: 0; padding: 0; }
                .messages li { background: #101c17; border: 1px solid rgba(220, 245, 230, 0.14); border-radius: 8px; display: grid; gap: 4px; padding: 10px 12px; }
                .messages strong { color: #ffffff; font-size: 13px; }
                .messages span { color: #91a89c; font-size: 11px; }
                .messages p { color: #c9d8d1; font-size: 13px; line-height: 1.4; margin: 0; overflow-wrap: anywhere; }
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

    private fun messagesPanelScript(): String =
        """
        <script>
        (function () {
            function statusFor(form) {
                return form.querySelector('[data-form-status]');
            }
            function setStatus(form, text) {
                var node = statusFor(form);
                if (node) node.textContent = text;
            }
            function submitForm(form) {
                var body = new URLSearchParams(new FormData(form));
                setStatus(form, 'Sending message...');
                fetch(form.getAttribute('action'), {
                    method: 'POST',
                    credentials: 'include',
                    headers: {
                        'Accept': 'application/json',
                        'Content-Type': 'application/x-www-form-urlencoded'
                    },
                    body: body
                }).then(function (response) {
                    if (!response.ok) throw new Error('HTTP ' + response.status);
                    return response.json();
                }).then(function (payload) {
                    setStatus(form, 'Message sent.');
                    form.reset();
                    var thread = payload.thread || {};
                    var id = thread.id || payload.thread_id || '';
                    if (id) {
                        window.location.href = '/world/panels/messages?thread=' + encodeURIComponent(id) + '&surface=android_carry';
                    } else {
                        window.location.href = '/world/panels/messages?surface=android_carry';
                    }
                }).catch(function () {
                    setStatus(form, 'Unable to send message.');
                });
            }
            document.querySelectorAll('form[data-create-thread], form[data-reply-thread]').forEach(function (form) {
                form.addEventListener('submit', function (event) {
                    event.preventDefault();
                    submitForm(form);
                });
            });
        }());
        </script>
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
