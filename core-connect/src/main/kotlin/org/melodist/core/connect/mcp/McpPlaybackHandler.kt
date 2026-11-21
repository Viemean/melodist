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

    suspend fun getPlaybackStatus(): Result<JsonObject>

    suspend fun getPlaybackQueue(
        offset: Int = 0,
        limit: Int = 50,
    ): Result<JsonObject>

    suspend fun getPlaybackHistory(
        type: String = "song",
        limit: Int = 20,
    ): Result<JsonArray>
}
