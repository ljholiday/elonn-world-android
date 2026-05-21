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
    val source: String
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
        return WorldRuntime(
            fieldObjects = fieldObjects(layout.optJSONArray("field")),
            carrySurfaces = carrySurfaces(layout.optJSONArray("carry")),
            source = source
        )
    }

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

    private fun carrySurfaces(carry: JSONArray?): List<CarrySurface> {
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
                        runtimePanelUrl = state.optString("runtime_panel_url").ifBlank { null }
                    )
                )
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
        CarrySurface("find_object", "Find", "Find is unavailable while World is offline.", null),
        CarrySurface("social_object", "Social", "Social is unavailable while World is offline.", null),
        CarrySurface("calendar_object", "Calendar", "Calendar is unavailable while World is offline.", null),
        CarrySurface("messages_object", "Messages", "Messages are unavailable while World is offline.", null),
        CarrySurface("settings_object", "Settings", "World runtime settings are unavailable while offline.", null)
    )
