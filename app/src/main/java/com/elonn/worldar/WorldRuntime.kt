package com.elonn.worldar

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

data class WorldRuntime(
    val fieldObjects: List<WorldObject>,
    val carrySurfaces: List<CarrySurface>,
    val surfaceRuntime: RuntimeSurfaceContract?,
    val leftContextRail: ContextRailContent,
    val rightContextRail: ContextRailContent,
    val source: String
)

data class RuntimeSurfaceContract(
    val surfaces: List<RuntimeSurface>,
    val stacks: List<RuntimeSurfaceStack>,
    val focusedSurfaceId: String?
)

data class RuntimeSurface(
    val surfaceId: String,
    val surfaceType: String,
    val title: String,
    val contentUrl: String?,
    val placementMode: String,
    val stackId: String?
)

data class RuntimeSurfaceStack(
    val stackId: String,
    val stackType: String,
    val focusedSurfaceId: String?,
    val surfaceOrder: List<String>,
    val viewportId: String?
)

data class ContextRailContent(
    val title: String,
    val status: String,
    val rows: List<ContextRailRow>
)

data class ContextRailRow(
    val title: String,
    val detail: String,
    val key: String = title
)

class WorldRuntimeClient(
    private val baseUrl: String = WORLD_BASE_URL
) {
    fun fetch(token: String): WorldRuntime {
        val connection = (URL(baseUrl.trimEnd('/') + "/world/session").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Cookie", "elonn_api_token=$token")
            setRequestProperty("User-Agent", "ElonnWorldAndroid/0.1 RuntimeClient/1")
            connectTimeout = 5000
            readTimeout = 5000
            instanceFollowRedirects = true
        }

        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299 || body.isBlank()) {
                throw IllegalStateException("World runtime request failed with HTTP $status")
            }

            return WorldRuntimeParser.parse(JSONObject(body), baseUrl)
        } finally {
            connection.disconnect()
        }
    }
}

object WorldRuntimeParser {
    fun parse(payload: JSONObject, source: String): WorldRuntime {
        val layout = payload.optJSONObject("layout") ?: JSONObject()
        val surfaceRuntime = surfaceRuntime(payload.optJSONObject("surface_runtime"))
        return WorldRuntime(
            fieldObjects = fieldObjects(fieldArray(payload, layout)),
            carrySurfaces = carrySurfaces(surfaceRuntime, layout.optJSONArray("carry")),
            surfaceRuntime = surfaceRuntime,
            leftContextRail = worldContextRail(payload, layout),
            rightContextRail = socialContextRail(payload),
            source = source
        )
    }

    private fun fieldArray(payload: JSONObject, layout: JSONObject): JSONArray? =
        layout.optJSONArray("field")
            ?: payload.optJSONObject("maps")?.optJSONArray("field")
            ?: payload.optJSONObject("services")
                ?.optJSONObject("maps")
                ?.optJSONObject("objects")
                ?.optJSONArray("field")
            ?: payload.optJSONObject("objects")?.optJSONArray("field")

    private fun fieldObjects(field: JSONArray?): List<WorldObject> {
        if (field == null) {
            return emptyList()
        }

        return buildList {
            for (index in 0 until field.length()) {
                val item = field.optJSONObject(index) ?: continue
                add(fieldObject(item))
            }
        }
    }

    private fun fieldObject(item: JSONObject): WorldObject {
        val state = stateObject(item)
        val id = item.optString("object_key", item.optString("id", "field_object"))
        val title = item.optString("title", id)
        val type = state.optString("marker_subtitle", id)
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .ifBlank { "field_object" }
        val explicitLatitude = item.optNullableDouble("latitude")
        val explicitLongitude = item.optNullableDouble("longitude")

        if (explicitLatitude != null && explicitLongitude != null) {
            return WorldObject(
                id = id,
                type = type,
                label = title,
                latitude = explicitLatitude,
                longitude = explicitLongitude
            )
        }

        return nearbyWorldObject(
            id = id,
            type = type,
            label = title,
            bearingDegrees = state.optDouble("bearing_degrees", 0.0).toFloat(),
            distanceMeters = state.optDouble("distance_meters", fallbackDistanceFor(item))
        )
    }

    private fun carrySurfaces(surfaceRuntime: RuntimeSurfaceContract?, carry: JSONArray?): List<CarrySurface> {
        if (surfaceRuntime != null && surfaceRuntime.surfaces.isNotEmpty()) {
            val order = surfaceRuntime.stacks.firstOrNull()?.surfaceOrder.orEmpty()
            val byId = surfaceRuntime.surfaces.associateBy { it.surfaceId }
            val ordered = order.mapNotNull { byId[it] }.ifEmpty { surfaceRuntime.surfaces }
            return ordered.map { surface ->
                CarrySurface(
                    key = surface.surfaceId,
                    title = surface.title,
                    panelText = "${surface.title} is not available yet.",
                    runtimePanelUrl = surface.contentUrl,
                    surfaceId = surface.surfaceId
                )
            }
        }

        if (carry == null) {
            return emptyList()
        }

        return buildList {
            for (index in 0 until carry.length()) {
                val item = carry.optJSONObject(index) ?: continue
                val state = stateObject(item)
                val key = item.optString("object_key", "carry_object_$index")
                val title = item.optString("title", key)
                add(
                    CarrySurface(
                        key = key,
                        title = title,
                        panelText = state.optString("panel", "$title is not available yet."),
                        runtimePanelUrl = state.optString("runtime_panel_url").ifBlank { null },
                        surfaceId = key
                    )
                )
            }
        }
    }

    private fun surfaceRuntime(payload: JSONObject?): RuntimeSurfaceContract? {
        if (payload == null) {
            return null
        }

        val surfaces = runtimeSurfaces(payload.optJSONArray("surfaces"))
        val stacks = runtimeStacks(payload.optJSONArray("stacks"))
        return RuntimeSurfaceContract(
            surfaces = surfaces,
            stacks = stacks,
            focusedSurfaceId = stacks.firstOrNull()?.focusedSurfaceId
        )
    }

    private fun runtimeSurfaces(source: JSONArray?): List<RuntimeSurface> {
        if (source == null) {
            return emptyList()
        }

        return buildList {
            for (index in 0 until source.length()) {
                val item = source.optJSONObject(index) ?: continue
                val contentSource = item.optJSONObject("content_source") ?: JSONObject()
                val placement = item.optJSONObject("placement") ?: JSONObject()
                val surfaceId = item.optString("surface_id", "surface_$index")
                add(
                    RuntimeSurface(
                        surfaceId = surfaceId,
                        surfaceType = item.optString("surface_type", "web_surface"),
                        title = item.optString("title", surfaceId),
                        contentUrl = contentSource.optString("url").ifBlank { null },
                        placementMode = placement.optString("mode", "stacked"),
                        stackId = placement.optString("stack_id").ifBlank { null }
                    )
                )
            }
        }
    }

    private fun runtimeStacks(source: JSONArray?): List<RuntimeSurfaceStack> {
        if (source == null) {
            return emptyList()
        }

        return buildList {
            for (index in 0 until source.length()) {
                val item = source.optJSONObject(index) ?: continue
                add(
                    RuntimeSurfaceStack(
                        stackId = item.optString("stack_id", "stack_$index"),
                        stackType = item.optString("stack_type", "carry_stack"),
                        focusedSurfaceId = item.optString("focused_surface_id").ifBlank { null },
                        surfaceOrder = stringArray(item.optJSONArray("surface_order")),
                        viewportId = item.optString("viewport_id").ifBlank { null }
                    )
                )
            }
        }
    }

    private fun stringArray(source: JSONArray?): List<String> {
        if (source == null) {
            return emptyList()
        }

        return buildList {
            for (index in 0 until source.length()) {
                val value = source.optString(index)
                if (value.isNotBlank()) {
                    add(value)
                }
            }
        }
    }

    private fun stateObject(item: JSONObject): JSONObject {
        val state = item.opt("state")
        if (state is JSONObject) {
            return state
        }

        val encoded = item.optString("state_json", "")
        if (encoded.isBlank()) {
            return JSONObject()
        }

        return runCatching { JSONObject(encoded) }.getOrDefault(JSONObject())
    }

    private fun worldContextRail(payload: JSONObject, layout: JSONObject): ContextRailContent {
        val contract = payload.optJSONObject("contract") ?: JSONObject()
        val session = payload.optJSONObject("session") ?: JSONObject()
        val services = payload.optJSONObject("services") ?: JSONObject()
        val identityMembers = services
            .optJSONObject("identity")
            ?.optJSONObject("objects")
            ?.optJSONArray("members")
            ?: payload.optJSONObject("identity")?.optJSONArray("members")
        val mapsObjects = payload.optJSONObject("maps") ?: JSONObject()
        val field = layout.optJSONArray("field") ?: mapsObjects.optJSONArray("field")
        val carry = layout.optJSONArray("carry")

        return ContextRailContent(
            title = "World Contract",
            status = "World runtime session loaded.",
            rows = listOf(
                ContextRailRow("Contract", contract.optString("name", "unknown"), "contract"),
                ContextRailRow("Version", contract.optString("version", "0"), "version"),
                ContextRailRow(
                    "Persistence",
                    session.optString("persistence_authority", "world"),
                    "persistence"
                ),
                ContextRailRow("Members", (identityMembers?.length() ?: 0).toString(), "members"),
                ContextRailRow("Map objects", (field?.length() ?: 0).toString(), "map_objects"),
                ContextRailRow("Carry objects", (carry?.length() ?: 0).toString(), "carry_objects")
            )
        )
    }

    private fun socialContextRail(payload: JSONObject): ContextRailContent {
        val objects = payload
            .optJSONObject("services")
            ?.optJSONObject("social")
            ?.optJSONObject("objects")
            ?: JSONObject()
        val conversations = objects.optJSONArray("conversations") ?: JSONArray()
        val communities = objects.optJSONArray("communities") ?: JSONArray()
        val events = objects.optJSONArray("events") ?: JSONArray()
        val presence = objects.optJSONArray("presence") ?: JSONArray()
        val messages = objects.optJSONArray("messages") ?: JSONArray()

        return ContextRailContent(
            title = "Social Menu",
            status = "${conversations.length()} conversations, ${communities.length()} communities, " +
                "${events.length()} events, ${presence.length()} presence signals, " +
                "${messages.length()} message threads.",
            rows = buildList {
                add(ContextRailRow("Open workspace", "Open the main social interface.", "social_workspace"))
                add(ContextRailRow("Create conversation", "Start a new thread.", "create_conversation"))
                add(ContextRailRow("Create community", "Start a new community.", "create_community"))
                add(ContextRailRow("Conversations", "${conversations.length()} threads", "conversations"))
                add(ContextRailRow("Communities", "${communities.length()} groups", "communities"))
                add(ContextRailRow("Events", "${events.length()} gatherings", "events"))
                add(ContextRailRow("Presence", "${presence.length()} signals", "presence"))
                add(ContextRailRow("Messages", "${messages.length()} threads", "messages"))
                appendSocialRows(conversations, "conversation", 3) { item ->
                    item.optString("title", item.optString("name", "Conversation")) to
                        item.optString("summary", item.optString("description", "No summary available."))
                }
                appendSocialRows(communities, "community", 3) { item ->
                    item.optString("name", item.optString("title", "Community")) to
                        item.optString("summary", item.optString("description", "No summary available."))
                }
                appendSocialRows(events, "event", 3) { item ->
                    item.optString("title", item.optString("name", "Event")) to
                        item.optString("summary", item.optString("visibility", "No summary available."))
                }
                appendSocialRows(presence, "presence", 3) { item ->
                    val identity = item.optString("identity_user_id", item.optString("name", "Presence"))
                    val status = item.optString("status", "active")
                    val availability = item.optString("availability", "").ifBlank { "available" }
                    identity to "$status - $availability"
                }
                appendMessageRows(messages, 5)
            }
        )
    }

    private fun MutableList<ContextRailRow>.appendMessageRows(source: JSONArray, limit: Int) {
        for (index in 0 until minOf(source.length(), limit)) {
            val item = source.optJSONObject(index) ?: continue
            val id = item.optString("id", index.toString())
            val title = item.optString("title", item.optString("participant_label", "Message thread"))
            val detail = item.optString("summary", item.optString("participant_label", "Direct messages"))
            add(ContextRailRow(title, detail, "message_thread:$id"))
        }
    }

    private fun MutableList<ContextRailRow>.appendSocialRows(
        source: JSONArray,
        keyPrefix: String,
        limit: Int,
        mapper: (JSONObject) -> Pair<String, String>
    ) {
        for (index in 0 until minOf(source.length(), limit)) {
            val item = source.optJSONObject(index) ?: continue
            val (title, detail) = mapper(item)
            add(ContextRailRow(title, detail, "$keyPrefix:$index"))
        }
    }

    private fun nearbyWorldObject(
        id: String,
        type: String,
        label: String,
        bearingDegrees: Float,
        distanceMeters: Double
    ): WorldObject {
        val origin = PlaceholderWorldObjects.deviceLocation
        val bearingRadians = bearingDegrees * PI / 180.0
        val latitudeOffset = distanceMeters * cos(bearingRadians) / METERS_PER_DEGREE_LATITUDE
        val longitudeOffset = distanceMeters * sin(bearingRadians) /
            (METERS_PER_DEGREE_LATITUDE * cos(origin.latitude * PI / 180.0))

        return WorldObject(
            id = id,
            type = type,
            label = label,
            latitude = origin.latitude + latitudeOffset,
            longitude = origin.longitude + longitudeOffset
        )
    }

    private fun fallbackDistanceFor(item: JSONObject): Double {
        val x = item.optDouble("position_x", 0.0)
        return (3.0 + (x % 700.0) / 140.0).coerceIn(3.0, 8.0)
    }

    private fun JSONObject.optNullableDouble(key: String): Double? =
        if (has(key) && !isNull(key)) optDouble(key) else null

    private const val METERS_PER_DEGREE_LATITUDE = 111_320.0
}

fun fallbackCarrySurfaces(): List<CarrySurface> =
    listOf(
        CarrySurface("find_object", "Find", "Find is unavailable while World is offline.", "/world/panels/find"),
        CarrySurface("social_object", "Social", "Social is unavailable while World is offline.", "/world/panels/social"),
        CarrySurface("events_object", "Events", "Events are unavailable while World is offline.", "/world/panels/social?view=events"),
        CarrySurface("calendar_object", "Calendar", "Calendar is unavailable while World is offline.", "/world/panels/time"),
        CarrySurface("messages_object", "Messages", "Messages are unavailable while World is offline.", "/world/panels/messages"),
        CarrySurface("settings_object", "Settings", "World runtime settings are unavailable while offline.", null)
    )

fun fallbackLeftContextRail(): ContextRailContent =
    ContextRailContent(
        title = "World Contract",
        status = "World runtime unavailable.",
        rows = listOf(
            ContextRailRow("Service", "Offline", "service"),
            ContextRailRow("Persistence", "world", "persistence"),
            ContextRailRow("Map objects", PlaceholderWorldObjects.objects.size.toString(), "map_objects"),
            ContextRailRow("Carry objects", fallbackCarrySurfaces().size.toString(), "carry_objects")
        )
    )

fun fallbackRightContextRail(): ContextRailContent =
    ContextRailContent(
        title = "Social Menu",
        status = "Social context unavailable.",
        rows = listOf(
            ContextRailRow("Workspace", "Sign in to load social objects.", "social_workspace")
        )
    )
