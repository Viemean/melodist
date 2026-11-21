package org.melodist.core.connect.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

interface McpPlaybackHandler {
    suspend fun play(
        target: String,
        id: String,
    ): Result<String>

    suspend fun controlPlayback(
        action: String,
        mode: String?,
    ): Result<String>

    suspend fun getPlaybackStatus(
        queueOffset: Int = 0,
        queueLimit: Int = 50,
    ): Result<JsonObject>

    suspend fun getPlaybackHistory(
        type: String = "song",
        limit: Int = 20,
    ): Result<JsonArray>
}
