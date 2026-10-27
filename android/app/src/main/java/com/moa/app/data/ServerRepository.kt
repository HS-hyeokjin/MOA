package com.moa.app.data

import com.moa.app.BuildConfig
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class ServerRepository {
    suspend fun checkConnection(): Unit = withContext(Dispatchers.IO) {
        val connection = URI("${BuildConfig.API_BASE_URL}/api/v1/health")
            .toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("Unexpected health response")
            }
            // A tunnel warning page must never be mistaken for a healthy API.
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val health = JSONObject(body)
            if (health.optString("application") != "MOA" || health.optString("status") != "UP") {
                throw IOException("Invalid health response")
            }
        } finally {
            connection.disconnect()
        }
    }
}
