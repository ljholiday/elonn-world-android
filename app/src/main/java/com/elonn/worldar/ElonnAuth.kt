package com.elonn.worldar

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class AuthSession(
    val token: String,
    val expiresAt: String
)

class ElonnAuthClient(
    private val baseUrl: String = API_BASE_URL
) {
    fun login(email: String, password: String): AuthSession {
        val body = JSONObject()
            .put("email", email)
            .put("password", password)
            .toString()

        val connection = (URL(baseUrl.trimEnd('/') + "/identity/login").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", "ElonnWorldAndroid/0.1 AuthClient/1")
            doOutput = true
            connectTimeout = 5000
            readTimeout = 5000
        }

        try {
            connection.outputStream.use { stream ->
                stream.write(body.toByteArray(Charsets.UTF_8))
            }

            val responseBody = connection.responseBody()
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("Login failed with HTTP ${connection.responseCode}")
            }

            val json = JSONObject(responseBody)
            val token = json.optString("token")
            val expiresAt = json.optString("expires_at")
            if (token.isBlank() || expiresAt.isBlank()) {
                throw IllegalStateException("Login response did not include a token")
            }

            return AuthSession(token, expiresAt)
        } finally {
            connection.disconnect()
        }
    }

    fun isTokenValid(token: String): Boolean {
        val connection = (URL(baseUrl.trimEnd('/') + "/identity/me").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("User-Agent", "ElonnWorldAndroid/0.1 AuthClient/1")
            connectTimeout = 5000
            readTimeout = 5000
        }

        return try {
            connection.responseCode == 200
        } finally {
            connection.disconnect()
        }
    }

    private fun HttpURLConnection.responseBody(): String {
        val stream = if (responseCode in 200..299) inputStream else errorStream
        return stream?.bufferedReader()?.use { it.readText() }.orEmpty()
    }
}

const val API_BASE_URL = "https://api.elonn.com"
