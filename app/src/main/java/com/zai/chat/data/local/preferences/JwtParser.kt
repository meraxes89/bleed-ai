package com.zai.chat.data.local.preferences

import android.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class JwtClaims(
    val rawToken: String,
    val subject: String?,
    val email: String? = null,
    val role: String?,
    val issuer: String?,
    val expiresAtEpochSeconds: Long?,
    val isExpired: Boolean,
    val humanReadableExpiry: String
)

object JwtParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(rawToken: String?): JwtClaims? {
        if (rawToken.isNullOrBlank()) return null
        val cleanToken = rawToken.removePrefix("Bearer ").trim()
        val parts = cleanToken.split(".")
        if (parts.size < 2) return null

        return try {
            val payloadBytes = Base64.decode(parts[1], Base64.URL_SAFE or Base64.DEFAULT)
            val payloadString = String(payloadBytes, StandardCharsets.UTF_8)
            val jsonObject = json.decodeFromString<JsonObject>(payloadString)

            val exp = jsonObject["exp"]?.jsonPrimitive?.longOrNull
            val sub = jsonObject["sub"]?.jsonPrimitive?.contentOrNull
                ?: jsonObject["id"]?.jsonPrimitive?.contentOrNull
                ?: jsonObject["email"]?.jsonPrimitive?.contentOrNull
            val email = jsonObject["email"]?.jsonPrimitive?.contentOrNull
            val role = jsonObject["role"]?.jsonPrimitive?.contentOrNull
            val iss = jsonObject["iss"]?.jsonPrimitive?.contentOrNull

            val nowSeconds = System.currentTimeMillis() / 1000
            val isExpired = exp != null && exp < nowSeconds

            val humanExpiry = if (exp != null) {
                val date = Date(exp * 1000)
                val sdf = SimpleDateFormat("MMM dd, yyyy HH:mm:ss", Locale.getDefault())
                if (isExpired) "Expired on ${sdf.format(date)}" else "Valid until ${sdf.format(date)}"
            } else {
                "No expiration defined (Perpetual)"
            }

            JwtClaims(
                rawToken = cleanToken,
                subject = sub,
                email = email,
                role = role,
                issuer = iss,
                expiresAtEpochSeconds = exp,
                isExpired = isExpired,
                humanReadableExpiry = humanExpiry
            )
        } catch (_: Exception) {
            null
        }
    }
}
