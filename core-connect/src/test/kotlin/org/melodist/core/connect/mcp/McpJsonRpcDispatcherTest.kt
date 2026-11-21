package org.melodist.core.connect.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class McpJsonRpcDispatcherTest {
    private lateinit var dispatcher: McpJsonRpcDispatcher
    private val fakePlaybackHandler =
        object : McpPlaybackHandler {
            override suspend fun play(
                target: String,
                id: String,
            ): Result<String> = Result.success("Playing $target $id")

            override suspend fun controlPlayback(
                action: String,
                mode: String?,
            ): Result<String> = Result.success("Controlled $action $mode")

            override suspend fun getPlaybackStatus(
                queueOffset: Int,
                queueLimit: Int,
            ): Result<JsonObject> =
                Result.success(
                    buildJsonObject {
                        put("isPlaying", true)
                        put("positionMs", 12345)
                        put("durationMs", 240000)
                        put("loopMode", "ListRepeat")
                        put("loopModeLabel", "列表循环")
                    },
                )

            override suspend fun getPlaybackHistory(
                type: String,
                limit: Int,
            ): Result<JsonArray> = Result.success(buildJsonArray {})
        }

    @BeforeEach
    fun setUp() {
        dispatcher =
            McpJsonRpcDispatcher(
                playbackHandler = fakePlaybackHandler,
                isDeleteAllowedProvider = { true },
                getCurrentPlayingMid = { "004Z8Ihr0JIu5s" },
            )
    }

    @Test
    fun `tools call conforms to standard MCP text content schema`() =
        runBlocking {
            val req =
                """
                {
                    "jsonrpc": "2.0",
                    "id": 1,
                    "method": "tools/call",
                    "params": {
                        "name": "get_playback_status",
                        "arguments": {}
                    }
                }
                """.trimIndent()

            val respStr = dispatcher.dispatch(req)
            assertNotNull(respStr)

            val resp = Json.parseToJsonElement(respStr!!).jsonObject
            val result = resp["result"]?.jsonObject
            assertNotNull(result)

            val content = result!!["content"]?.jsonArray
            assertNotNull(content)
            assertTrue(content!!.isNotEmpty())

            val firstItem = content[0].jsonObject
            assertEquals("text", firstItem["type"]?.jsonPrimitive?.content)
            assertNotNull(firstItem["text"]?.jsonPrimitive?.content)
            assertEquals(false, result["isError"]?.jsonPrimitive?.booleanOrNull)
        }

    @Test
    fun `tools list exposes complete catalog of tools`() =
        runBlocking {
            val req =
                """
                {
                    "jsonrpc": "2.0",
                    "id": 2,
                    "method": "tools/list"
                }
                """.trimIndent()

            val respStr = dispatcher.dispatch(req)
            assertNotNull(respStr)

            val resp = Json.parseToJsonElement(respStr!!).jsonObject
            val tools = resp["result"]?.jsonObject?.get("tools")?.jsonArray
            assertNotNull(tools)
            assertEquals(10, tools!!.size)
        }
}
