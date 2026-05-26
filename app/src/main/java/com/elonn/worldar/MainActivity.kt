package com.elonn.worldar

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.Surface
import android.view.View
import androidx.activity.OnBackPressedCallback
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.ar.core.Anchor
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/*
 * Minimal spatial proof for Elonn World's two-layer model.
 * Room objects are ARCore anchors projected into Android views; carry objects stay fixed on top.
 */
class MainActivity : AppCompatActivity(), WebPanelHost {
    private val tag = "ElonnWorldAr"

    private lateinit var arSurface: GLSurfaceView
    private lateinit var roomWorldMarkers: FrameLayout
    private lateinit var carryLayerRoot: View
    private lateinit var carryAppDockRoot: View
    private lateinit var carryActiveWindowRoot: View
    private lateinit var carryAppDock: CarryAppDock
    private lateinit var carrySideRails: CarrySideRails
    private lateinit var surfaceStack: SurfaceStackController
    private lateinit var loginOverlay: View
    private lateinit var loginEmail: EditText
    private lateinit var loginPassword: EditText
    private lateinit var loginSubmit: Button
    private lateinit var loginStatus: TextView
    private lateinit var renderer: SpatialRenderer

    private var arSession: Session? = null
    private var installRequested = false
    private var isSessionResumed = false
    private var authToken: String? = null
    private var pendingSocialFileCallback: ValueCallback<Array<Uri>>? = null
    private val smoothedRoomObjectPositions = mutableMapOf<String, ScreenPoint>()

    private var roomWorldMarkerObjects = PlaceholderWorldObjects.objects
    private var carrySurfaces = fallbackCarrySurfaces()
    private var leftContextRail = fallbackLeftContextRail()
    private var rightContextRail = fallbackRightContextRail()
    private val roomObjectViews = mutableMapOf<String, View>()

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Log.d(tag, "camera permission result granted=$granted")
            if (granted) {
                resumeArSession()
            }
        }

    private val socialFileChooser =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = pendingSocialFileCallback ?: return@registerForActivityResult
            pendingSocialFileCallback = null
            val uris = WebChromeClient.FileChooserParams.parseResult(
                result.resultCode,
                result.data
            )
            callback.onReceiveValue(
                if (result.resultCode == Activity.RESULT_OK) uris else null
            )
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(tag, "onCreate")
        setContentView(R.layout.activity_main)

        arSurface = findViewById(R.id.ar_surface)
        roomWorldMarkers = findViewById(R.id.room_world_markers)
        carryLayerRoot = findViewById(R.id.carry_layer)
        carryAppDockRoot = findViewById(R.id.carry_app_dock)
        carryActiveWindowRoot = findViewById(R.id.carry_active_window)
        loginOverlay = findViewById(R.id.login_overlay)
        loginEmail = findViewById(R.id.login_email)
        loginPassword = findViewById(R.id.login_password)
        loginSubmit = findViewById(R.id.login_submit)
        loginStatus = findViewById(R.id.login_status)
        carryAppDock = CarryAppDock(
            root = carryAppDockRoot,
            onSelected = { surface -> showActiveCarryWindow(surface) }
        )
        carrySideRails = CarrySideRails(
            leftRail = findViewById(R.id.carry_left_siderail),
            leftHandle = findViewById(R.id.carry_left_siderail_handle),
            leftItems = findViewById<LinearLayout>(R.id.carry_left_siderail_items),
            rightRail = findViewById(R.id.carry_right_siderail),
            rightHandle = findViewById(R.id.carry_right_siderail_handle),
            rightItems = findViewById<LinearLayout>(R.id.carry_right_siderail_items),
            onRowSelected = { row -> openContextRailRow(row) }
        )
        surfaceStack = SurfaceStackController(
            root = carryActiveWindowRoot,
            contentContainer = findViewById(R.id.active_window_body),
            closeControl = findViewById(R.id.active_window_close),
            panelHost = CarryPanelHost(
                fileChooserHost = this,
                authTokenProvider = { authToken }
            )
        )
        renderer = SpatialRenderer(
            worldObjects = roomWorldMarkerObjects,
            deviceLocation = PlaceholderWorldObjects.deviceLocation,
            logMarkerCreated = { message -> Log.d(tag, message) },
            rotationProvider = { displayRotation() },
            onFrame = { projections -> runOnUiThread { updateRoomObjectPositions(projections) } }
        )

        arSurface.setEGLContextClientVersion(2)
        arSurface.setRenderer(renderer)
        arSurface.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        configureCarryRegions()
        configureBackNavigation()
        configureLogin()
        inflateRoomWorldMarkers()
        carryLayerRoot.bringToFront()
        resumeAuthenticatedRuntime()
    }

    override fun onResume() {
        super.onResume()
        Log.d(tag, "onResume")
        if (hasCameraPermission()) {
            resumeArSession()
        } else {
            Log.d(tag, "requesting camera permission")
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
        arSurface.onResume()
    }

    override fun onPause() {
        Log.d(tag, "onPause")
        super.onPause()
        arSurface.onPause()
        renderer.setSession(null)
        arSession?.pause()
        isSessionResumed = false
    }

    override fun onDestroy() {
        Log.d(tag, "onDestroy")
        super.onDestroy()
        pendingSocialFileCallback?.onReceiveValue(null)
        pendingSocialFileCallback = null
        renderer.clearSession()
        arSession?.close()
        arSession = null
        isSessionResumed = false
    }

    override fun openFileChooser(
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: WebChromeClient.FileChooserParams
    ): Boolean {
        pendingSocialFileCallback?.onReceiveValue(null)
        pendingSocialFileCallback = filePathCallback

        return try {
            socialFileChooser.launch(fileChooserParams.createIntent())
            true
        } catch (_: ActivityNotFoundException) {
            pendingSocialFileCallback = null
            filePathCallback.onReceiveValue(null)
            false
        }
    }

    private fun resumeArSession() {
        val session = ensureArSession() ?: return
        if (isSessionResumed) {
            Log.d(tag, "AR session already resumed")
            return
        }

        try {
            Log.d(tag, "resuming AR session")
            renderer.setSession(session)
            session.resume()
            isSessionResumed = true
        } catch (_: CameraNotAvailableException) {
            renderer.setSession(null)
            isSessionResumed = false
            showSpatialStatus("Camera is not available for AR tracking.")
        }
    }

    private fun ensureArSession(): Session? {
        arSession?.let { return it }

        try {
            when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    Log.d(tag, "ARCore install requested")
                    installRequested = true
                    return null
                }

                ArCoreApk.InstallStatus.INSTALLED -> Unit
            }

            Log.d(tag, "creating AR session")
            val session = Session(this).apply {
                configure(
                    Config(this).apply {
                        updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    }
                )
            }
            arSession = session
            return session
        } catch (_: UnavailableException) {
            Log.d(tag, "ARCore unavailable")
            showSpatialStatus("ARCore is not available on this device.")
        } catch (_: SecurityException) {
            Log.d(tag, "camera permission missing while creating AR session")
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
        return null
    }

    private fun inflateRoomWorldMarkers() {
        val inflater = LayoutInflater.from(this)

        roomWorldMarkers.removeAllViews()
        roomObjectViews.clear()
        smoothedRoomObjectPositions.clear()
        roomWorldMarkerObjects.forEach { roomObject ->
            val cardView = inflater.inflate(R.layout.view_room_object, roomWorldMarkers, false)
            cardView.findViewById<TextView>(R.id.roomObjectLabel).text = "World Marker"
            cardView.findViewById<TextView>(R.id.roomObjectTitle).text = roomObject.label
            cardView.findViewById<TextView>(R.id.roomObjectSubtitle).text = roomObject.type.replace('_', ' ')
            cardView.visibility = View.INVISIBLE
            roomWorldMarkers.addView(cardView)
            roomObjectViews[roomObject.id] = cardView
        }
    }

    private fun configureCarryRegions() {
        carryAppDock.bind(carrySurfaces)
        surfaceStack.bind(carrySurfaces)
        carrySideRails.bind(leftContextRail, rightContextRail)
    }

    private fun configureBackNavigation() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (!surfaceStack.handleBack()) {
                        if (!carrySideRails.handleBack()) {
                            isEnabled = false
                            onBackPressedDispatcher.onBackPressed()
                            isEnabled = true
                        }
                    }
                }
            }
        )
    }

    private fun configureLogin() {
        loginSubmit.setOnClickListener {
            val email = loginEmail.text?.toString()?.trim().orEmpty()
            val password = loginPassword.text?.toString().orEmpty()
            if (email.isBlank() || password.isBlank()) {
                showLogin("Email and password are required.", enabled = true)
                return@setOnClickListener
            }

            showLogin("Signing in...", enabled = false)
            Thread {
                val result = runCatching { ElonnAuthClient().login(email, password) }
                runOnUiThread {
                    result
                        .onSuccess { session ->
                            storeAuthToken(session.token)
                            authToken = session.token
                            seedRuntimeCookies(session.token)
                            loginPassword.text?.clear()
                            hideLogin()
                            loadWorldRuntime(session.token)
                        }
                        .onFailure { throwable ->
                            Log.w(tag, "login failed", throwable)
                            showLogin("Login failed. Check your email and password.", enabled = true)
                        }
                }
            }.start()
        }
    }

    private fun resumeAuthenticatedRuntime() {
        val storedToken = storedAuthToken()
        if (storedToken.isNullOrBlank()) {
            showLogin("Sign in to load your World runtime.", enabled = true)
            return
        }

        showLogin("Checking session...", enabled = false)
        Thread {
            val valid = runCatching { ElonnAuthClient().isTokenValid(storedToken) }.getOrDefault(false)
            runOnUiThread {
                if (valid) {
                    authToken = storedToken
                    seedRuntimeCookies(storedToken)
                    hideLogin()
                    loadWorldRuntime(storedToken)
                } else {
                    clearAuthToken()
                    authToken = null
                    showLogin("Session expired. Sign in again.", enabled = true)
                }
            }
        }.start()
    }

    private fun showActiveCarryWindow(surface: CarrySurface) {
        Log.d(tag, "showActiveCarryWindow key=${surface.key} title=${surface.title}")
        carrySideRails.focus(surface)
        carrySideRails.collapse()
        surfaceStack.focus(surface)
        carryLayerRoot.bringToFront()
    }

    private fun openContextRailRow(row: ContextRailRow) {
        val key = row.key.lowercase()
        val target = when {
            key.startsWith("message_thread:") -> {
                val threadId = row.key.substringAfter(':', "")
                surfaceForKey("messages_object")?.copy(
                    title = row.title,
                    runtimePanelUrl = "/world/panels/messages?thread=$threadId"
                )
            }
            key == "messages" || key == "messages_new" -> surfaceForKey("messages_object")
            key.contains("event") -> surfaceForKey("events_object")
                ?: surfaceForKey("social_object")?.copy(
                    title = "Events",
                    runtimePanelUrl = "/world/panels/social?view=events"
                )
            key.contains("message") -> surfaceForKey("messages_object")
            key.contains("communit") -> surfaceForKey("social_object")?.copy(
                runtimePanelUrl = "/world/panels/social?view=communities"
            )
            key.contains("presence") -> surfaceForKey("social_object")?.copy(
                runtimePanelUrl = "/world/panels/social?view=presence"
            )
            key.contains("conversation") || key.contains("social") || key.contains("workspace") ->
                surfaceForKey("social_object")
            else -> null
        }

        target?.let { showActiveCarryWindow(it) }
    }

    private fun surfaceForKey(key: String): CarrySurface? {
        val normalized = key.lowercase()
        val aliases = when {
            normalized.contains("message") -> listOf("message")
            normalized.contains("event") -> listOf("event")
            normalized.contains("calendar") -> listOf("calendar", "time")
            normalized.contains("social") || normalized.contains("communit") || normalized.contains("presence") ->
                listOf("social", "community", "presence")
            normalized.contains("find") || normalized.contains("search") -> listOf("find", "search")
            else -> listOf(normalized.removeSuffix("_object"))
        }

        return carrySurfaces.firstOrNull { surface ->
            surface.key.equals(key, ignoreCase = true)
        } ?: carrySurfaces.firstOrNull { surface ->
            val identity = "${surface.key} ${surface.surfaceId} ${surface.title}".lowercase()
            aliases.any { alias -> identity.contains(alias) }
        }
    }

    private fun loadWorldRuntime(token: String) {
        Thread {
            val result = runCatching { WorldRuntimeClient().fetch(token) }
            runOnUiThread {
                result
                    .onSuccess { runtime -> applyWorldRuntime(runtime) }
                    .onFailure { throwable ->
                        Log.w(tag, "World runtime unavailable; using offline fallback", throwable)
                        showSpatialStatus("World runtime unavailable. Showing offline proof markers.")
                    }
            }
        }.start()
    }

    private fun hideLogin() {
        loginOverlay.visibility = View.GONE
        loginSubmit.isEnabled = true
    }

    private fun showLogin(message: String, enabled: Boolean) {
        loginOverlay.visibility = View.VISIBLE
        loginOverlay.bringToFront()
        loginStatus.text = message
        loginEmail.isEnabled = enabled
        loginPassword.isEnabled = enabled
        loginSubmit.isEnabled = enabled
    }

    private fun storedAuthToken(): String? =
        getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE)
            .getString(AUTH_TOKEN_KEY, null)
            ?.takeIf { it.isNotBlank() }

    private fun storeAuthToken(token: String) {
        getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(AUTH_TOKEN_KEY, token)
            .apply()
    }

    private fun clearAuthToken() {
        getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(AUTH_TOKEN_KEY)
            .apply()
    }

    private fun seedRuntimeCookies(token: String) {
        android.webkit.CookieManager.getInstance().apply {
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

    private fun applyWorldRuntime(runtime: WorldRuntime) {
        val nextObjects = runtime.fieldObjects.ifEmpty { PlaceholderWorldObjects.objects }
        val nextSurfaces = runtime.carrySurfaces.ifEmpty { fallbackCarrySurfaces() }
        Log.d(
            tag,
            "loaded World runtime source=${runtime.source} field=${nextObjects.size} carry=${nextSurfaces.size}"
        )

        roomWorldMarkerObjects = nextObjects
        carrySurfaces = nextSurfaces
        leftContextRail = runtime.leftContextRail
        rightContextRail = runtime.rightContextRail
        inflateRoomWorldMarkers()
        carryAppDock.update(carrySurfaces)
        surfaceStack.update(carrySurfaces)
        carrySideRails.update(leftContextRail, rightContextRail)
        renderer.updateWorldObjects(roomWorldMarkerObjects, PlaceholderWorldObjects.deviceLocation)
    }

    private fun updateRoomObjectPositions(projections: List<ObjectProjection>) {
        projections.forEach { projection ->
            val view = roomObjectViews[projection.id] ?: return@forEach
            if (!projection.visible) {
                smoothedRoomObjectPositions.remove(projection.id)
                view.visibility = View.INVISIBLE
                return@forEach
            }

            val width = view.width.takeIf { it > 0 } ?: view.measuredWidth
            val height = view.height.takeIf { it > 0 } ?: view.measuredHeight
            val targetX = projection.x - width / 2f
            val targetY = projection.y - height / 2f
            val smoothedPoint = smoothedRoomObjectPositions.getOrPut(projection.id) {
                ScreenPoint(targetX, targetY)
            }

            smoothedPoint.x += (targetX - smoothedPoint.x) * ROOM_OBJECT_SMOOTHING
            smoothedPoint.y += (targetY - smoothedPoint.y) * ROOM_OBJECT_SMOOTHING
            view.x = smoothedPoint.x
            view.y = smoothedPoint.y
            view.visibility = View.VISIBLE
        }
    }

    private fun showSpatialStatus(message: String) {
        roomObjectViews.values.firstOrNull()?.let { firstView ->
            firstView.findViewById<TextView>(R.id.roomObjectTitle).text = "Spatial unavailable"
            firstView.findViewById<TextView>(R.id.roomObjectSubtitle).text = message
            firstView.visibility = View.VISIBLE
        }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun displayRotation(): Int = display?.rotation ?: Surface.ROTATION_0

    private companion object {
        private const val ROOM_OBJECT_SMOOTHING = 0.42f
        private const val AUTH_PREFS = "elonn_world_auth"
        private const val AUTH_TOKEN_KEY = "elonn_api_token"
    }
}

data class ObjectProjection(
    val id: String,
    val x: Float,
    val y: Float,
    val visible: Boolean
)

data class ScreenPoint(
    var x: Float,
    var y: Float
)

private class SpatialRenderer(
    worldObjects: List<WorldObject>,
    deviceLocation: DeviceLocation,
    private val logMarkerCreated: (String) -> Unit,
    private val rotationProvider: () -> Int,
    private val onFrame: (List<ObjectProjection>) -> Unit
) : GLSurfaceView.Renderer {
    private val lock = Any()
    private var worldObjects: List<WorldObject> = worldObjects
    private var deviceLocation: DeviceLocation = deviceLocation
    private var session: Session? = null
    private var cameraTextureId = 0
    private var viewportWidth = 1
    private var viewportHeight = 1
    private var worldAnchors: List<WorldObjectAnchor> = emptyList()
    private var trackingFrameCount = 0
    private lateinit var cameraBackground: CameraBackgroundRenderer

    fun setSession(session: Session?) {
        synchronized(lock) {
            this.session = session
            if (session != null && cameraTextureId != 0) {
                session.setCameraTextureName(cameraTextureId)
            }
        }
    }

    fun clearSession() {
        synchronized(lock) {
            worldAnchors.forEach { it.anchor.detach() }
            worldAnchors = emptyList()
            session = null
        }
    }

    fun updateWorldObjects(nextWorldObjects: List<WorldObject>, nextDeviceLocation: DeviceLocation) {
        synchronized(lock) {
            worldAnchors.forEach { it.anchor.detach() }
            worldAnchors = emptyList()
            trackingFrameCount = 0
            worldObjects = nextWorldObjects
            deviceLocation = nextDeviceLocation
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.03f, 0.06f, 0.07f, 1.0f)
        cameraBackground = CameraBackgroundRenderer()
        cameraTextureId = cameraBackground.createOnGlThread()
        synchronized(lock) {
            session?.setCameraTextureName(cameraTextureId)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width.coerceAtLeast(1)
        viewportHeight = height.coerceAtLeast(1)
        synchronized(lock) {
            session?.setDisplayGeometry(rotationProvider(), viewportWidth, viewportHeight)
        }
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val currentSession = synchronized(lock) { session } ?: return
        try {
            currentSession.setDisplayGeometry(rotationProvider(), viewportWidth, viewportHeight)
            val frame = currentSession.update()
            cameraBackground.draw(frame)
            val camera = frame.camera
            if (camera.trackingState != TrackingState.TRACKING) {
                trackingFrameCount = 0
                return
            }

            if (worldAnchors.isEmpty()) {
                trackingFrameCount += 1
                if (trackingFrameCount < REQUIRED_TRACKING_FRAMES_BEFORE_ANCHORING) {
                    return
                }
                val nextAnchors = synchronized(lock) {
                    createWorldAnchors(currentSession, camera.pose, worldObjects, deviceLocation)
                }
                synchronized(lock) {
                    worldAnchors = nextAnchors
                }
            }

            val anchors = synchronized(lock) { worldAnchors }
            onFrame(projectWorldAnchors(camera, anchors))
        } catch (_: CameraNotAvailableException) {
            // The Activity will retry when the session resumes.
        }
    }

    private fun createWorldAnchors(
        session: Session,
        originPose: Pose,
        worldObjects: List<WorldObject>,
        deviceLocation: DeviceLocation
    ): List<WorldObjectAnchor> =
        worldObjects.mapIndexed { index, worldObject ->
            val bearing = BearingMath.bearingDegrees(
                fromLatitude = deviceLocation.latitude,
                fromLongitude = deviceLocation.longitude,
                toLatitude = worldObject.latitude,
                toLongitude = worldObject.longitude
            )
            val relativeBearing = BearingMath.normalizeSignedDegrees(
                bearing - deviceLocation.headingDegrees
            )
            val distanceMeters = BearingMath.distanceMeters(
                fromLatitude = deviceLocation.latitude,
                fromLongitude = deviceLocation.longitude,
                toLatitude = worldObject.latitude,
                toLongitude = worldObject.longitude
            )
            val bearingRadians = Math.toRadians(relativeBearing.toDouble()).toFloat()
            val worldPoint = horizontalWorldPoint(
                originPose = originPose,
                relativeBearingRadians = bearingRadians,
                distanceMeters = distanceMeters,
                verticalOffset = (0.5f - verticalPositionFor(index)) *
                    ROOM_MARKER_VERTICAL_SCALE_METERS
            )
            logMarkerCreated(
                "created world marker id=${worldObject.id} label=${worldObject.label} " +
                    "bearing=$bearing relativeBearing=$relativeBearing distanceMeters=$distanceMeters " +
                    "world=(${worldPoint[0]}, ${worldPoint[1]}, ${worldPoint[2]})"
            )
            WorldObjectAnchor(
                worldObject = worldObject,
                anchor = session.createAnchor(
                    Pose.makeTranslation(worldPoint[0], worldPoint[1], worldPoint[2])
                )
            )
        }

    private fun horizontalWorldPoint(
        originPose: Pose,
        relativeBearingRadians: Float,
        distanceMeters: Float,
        verticalOffset: Float
    ): FloatArray {
        val cameraZAxis = FloatArray(3)
        originPose.getTransformedAxis(2, 1.0f, cameraZAxis, 0)

        val forwardX = -cameraZAxis[0]
        val forwardZ = -cameraZAxis[2]
        val forwardLength = sqrt(forwardX * forwardX + forwardZ * forwardZ)
            .takeIf { it > 0.001f } ?: 1.0f
        val unitForwardX = forwardX / forwardLength
        val unitForwardZ = forwardZ / forwardLength
        val unitRightX = -unitForwardZ
        val unitRightZ = unitForwardX

        val horizontalX = unitForwardX * cos(relativeBearingRadians) +
            unitRightX * sin(relativeBearingRadians)
        val horizontalZ = unitForwardZ * cos(relativeBearingRadians) +
            unitRightZ * sin(relativeBearingRadians)

        return floatArrayOf(
            originPose.tx() + horizontalX * distanceMeters,
            originPose.ty() + verticalOffset,
            originPose.tz() + horizontalZ * distanceMeters
        )
    }

    private fun projectWorldAnchors(
        camera: com.google.ar.core.Camera,
        worldAnchors: List<WorldObjectAnchor>
    ): List<ObjectProjection> {
        val viewMatrix = FloatArray(16)
        val projectionMatrix = FloatArray(16)
        val viewProjectionMatrix = FloatArray(16)

        camera.getViewMatrix(viewMatrix, 0)
        camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100.0f)
        Matrix.multiplyMM(viewProjectionMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

        return worldAnchors.map { marker ->
            val pose = marker.anchor.pose
            val point = floatArrayOf(pose.tx(), pose.ty(), pose.tz(), 1.0f)
            val clip = FloatArray(4)
            Matrix.multiplyMV(clip, 0, viewProjectionMatrix, 0, point, 0)

            if (clip[3] <= 0.0f || marker.anchor.trackingState != TrackingState.TRACKING) {
                return@map ObjectProjection(marker.worldObject.id, 0.0f, 0.0f, visible = false)
            }

            val normalizedX = clip[0] / clip[3]
            val normalizedY = clip[1] / clip[3]
            ObjectProjection(
                id = marker.worldObject.id,
                x = (normalizedX * 0.5f + 0.5f) * viewportWidth,
                y = (1.0f - (normalizedY * 0.5f + 0.5f)) * viewportHeight,
                visible = normalizedX in -1.15f..1.15f && normalizedY in -1.15f..1.15f
            )
        }
    }

    private fun verticalPositionFor(index: Int): Float {
        val positions = floatArrayOf(0.34f, 0.42f, 0.36f, 0.4f, 0.38f)
        return positions[index % positions.size]
    }

    private companion object {
        private const val REQUIRED_TRACKING_FRAMES_BEFORE_ANCHORING = 24
        private const val ROOM_MARKER_VERTICAL_SCALE_METERS = 1.3f
    }
}

private data class WorldObjectAnchor(
    val worldObject: WorldObject,
    val anchor: Anchor
)

private class CameraBackgroundRenderer {
    private val quadCoords = floatBufferOf(
        -1.0f, -1.0f,
        1.0f, -1.0f,
        -1.0f, 1.0f,
        1.0f, 1.0f
    )
    private val quadTexCoords = floatBufferOf(
        0.0f, 0.0f,
        1.0f, 0.0f,
        0.0f, 1.0f,
        1.0f, 1.0f
    )
    private val transformedTexCoords = floatBufferOf(
        0.0f, 0.0f,
        1.0f, 0.0f,
        0.0f, 1.0f,
        1.0f, 1.0f
    )

    private var textureId = 0
    private var program = 0
    private var positionAttribute = 0
    private var texCoordAttribute = 0
    private var textureUniform = 0

    fun createOnGlThread(): Int {
        textureId = createExternalTexture()
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        positionAttribute = GLES20.glGetAttribLocation(program, "a_Position")
        texCoordAttribute = GLES20.glGetAttribLocation(program, "a_TexCoord")
        textureUniform = GLES20.glGetUniformLocation(program, "u_Texture")
        return textureId
    }

    fun draw(frame: Frame) {
        if (frame.timestamp == 0L) {
            return
        }

        if (frame.hasDisplayGeometryChanged()) {
            quadCoords.position(0)
            transformedTexCoords.position(0)
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                quadCoords,
                Coordinates2d.TEXTURE_NORMALIZED,
                transformedTexCoords
            )
        }

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)
        GLES20.glUseProgram(program)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(textureUniform, 0)

        quadCoords.position(0)
        GLES20.glVertexAttribPointer(positionAttribute, 2, GLES20.GL_FLOAT, false, 0, quadCoords)
        GLES20.glEnableVertexAttribArray(positionAttribute)

        transformedTexCoords.position(0)
        GLES20.glVertexAttribPointer(
            texCoordAttribute,
            2,
            GLES20.GL_FLOAT,
            false,
            0,
            transformedTexCoords
        )
        GLES20.glEnableVertexAttribArray(texCoordAttribute)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(positionAttribute)
        GLES20.glDisableVertexAttribArray(texCoordAttribute)
        GLES20.glDepthMask(true)
    }

    private fun createExternalTexture(): Int {
        val textureIds = IntArray(1)
        GLES20.glGenTextures(1, textureIds, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureIds[0])
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR
        )
        return textureIds[0]
    }

    private fun createProgram(vertexShaderCode: String, fragmentShaderCode: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)
        return GLES20.glCreateProgram().also { program ->
            GLES20.glAttachShader(program, vertexShader)
            GLES20.glAttachShader(program, fragmentShader)
            GLES20.glLinkProgram(program)
        }
    }

    private fun loadShader(type: Int, shaderCode: String): Int =
        GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, shaderCode)
            GLES20.glCompileShader(shader)
        }

    private companion object {
        private const val VERTEX_SHADER = """
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;

            void main() {
                gl_Position = a_Position;
                v_TexCoord = a_TexCoord;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 v_TexCoord;
            uniform samplerExternalOES u_Texture;

            void main() {
                gl_FragColor = texture2D(u_Texture, v_TexCoord);
            }
        """
    }
}

private fun floatBufferOf(vararg values: Float): FloatBuffer =
    ByteBuffer.allocateDirect(values.size * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(values)
            position(0)
        }
