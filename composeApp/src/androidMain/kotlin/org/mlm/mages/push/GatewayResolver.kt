package org.mlm.mages.push

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/*
   Reference: Element X's Gateway resolver
 */
object GatewayResolver {
    private const val DEFAULT_GATEWAY = "https://matrix.gateway.unifiedpush.org/_matrix/push/v1/notify"

    suspend fun resolveGateway(endpoint: String): String = withContext(Dispatchers.IO) {
        val url = try {
            URL(endpoint)
        } catch (_: Exception) {
            return@withContext DEFAULT_GATEWAY
        }

        val port = if (url.port != -1) ":${url.port}" else ""
        val customBase = "${url.protocol}://${url.host}$port"
        val customUrl = "$customBase/_matrix/push/v1/notify"

        try {
            val connection = URL(customUrl).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000

            if (connection.responseCode == 200) {
                val response = connection.inputStream.bufferedReader().readText()
                val json = JSONObject(response)
                val gateway = json.optJSONObject("unifiedpush")?.optString("gateway")
                if (gateway == "matrix") {
                    return@withContext customUrl
                }
            }
            DEFAULT_GATEWAY
        } catch (_: Exception) {
            DEFAULT_GATEWAY
        }
    }
}