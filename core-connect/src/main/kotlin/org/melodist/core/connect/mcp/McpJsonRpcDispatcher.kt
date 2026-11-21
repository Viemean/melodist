package org.melodist.core.connect.mcp

import android.util.Log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.melodist.api.MusicApiService
import org.melodist.api.UserSession
import org.melodist.api.addSongToFavorite
import org.melodist.api.deleteSongFromFavorite
import org.melodist.api.getDailyRecommendDetail
import org.melodist.api.getFavoriteSongsDetail
import org.melodist.api.getGuessRecommendSongs
import org.melodist.api.getListeningRecordReport
import org.melodist.api.getLyrics
import org.melodist.api.getMillionRecommendDetail
import org.melodist.api.getSongDiary
import org.melodist.api.getSongListenRecords
import org.melodist.api.resolveSongId
import org.melodist.api.search
import org.melodist.api.searchAlbums
import org.melodist.api.searchArtists
import org.melodist.api.searchPlaylists
import org.melodist.model.ListeningReportPeriod
import org.melodist.model.Song
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class McpJsonRpcDispatcher(
    private val playbackHandler: McpPlaybackHandler,
    private val isDeleteAllowedProvider: () -> Boolean,
    private val getCurrentPlayingMid: () -> String? = { null },
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }

    private fun formatSuccessResponse(
        id: JsonElement?,
        result: JsonElement,
    ): String =
        json.encodeToString(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id ?: JsonNull)
                put("result", result)
            },
        )

    private fun formatErrorResponse(
        id: JsonElement?,
        error: JsonRpcError,
    ): String =
        json.encodeToString(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id ?: JsonNull)
                put("error", json.encodeToJsonElement(JsonRpcError.serializer(), error))
            },
        )

    suspend fun dispatch(requestStr: String): String? {
        val rawJson =
            try {
                json.parseToJsonElement(requestStr)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse JSON syntax", e)
                val err = JsonRpcError(code = -32700, message = "Parse error: ${e.message}")
                return formatErrorResponse(null, err)
            }

        val jsonObj =
            try {
                rawJson.jsonObject
            } catch (_: Exception) {
                val err = JsonRpcError(code = -32600, message = "Invalid Request: root must be a JSON object")
                return formatErrorResponse(null, err)
            }

        val id = jsonObj["id"]
        val methodPrimitive = jsonObj["method"] as? JsonPrimitive
        if (methodPrimitive == null || !methodPrimitive.isString || methodPrimitive.content.isBlank()) {
            val err = JsonRpcError(code = -32600, message = "Invalid Request: 'method' string is required")
            return formatErrorResponse(id, err)
        }

        val request =
            try {
                json.decodeFromJsonElement<JsonRpcRequest>(jsonObj)
            } catch (e: Exception) {
                val err = JsonRpcError(code = -32600, message = "Invalid Request: ${e.message}")
                return formatErrorResponse(id, err)
            }

        val isNotification = id == null || request.method.startsWith("notifications/")

        return when (request.method) {
            "initialize" -> {
                val result =
                    buildJsonObject {
                        put("protocolVersion", "2024-11-05")
                        put(
                            "capabilities",
                            buildJsonObject {
                                put("tools", buildJsonObject {})
                            },
                        )
                        put(
                            "serverInfo",
                            buildJsonObject {
                                put("name", "Melodist MCP Server")
                                put("version", "1.0.0")
                            },
                        )
                    }
                formatSuccessResponse(id, result)
            }

            "notifications/initialized", "notifications/cancelled" -> {
                null
            }

            "ping" -> {
                formatSuccessResponse(id, buildJsonObject {})
            }

            "tools/list" -> {
                val tools = buildToolsList()
                val result =
                    buildJsonObject {
                        put("tools", tools)
                    }
                formatSuccessResponse(id, result)
            }

            "tools/call" -> {
                val params = request.params?.jsonObject
                val toolName =
                    params
                        ?.get("name")
                        ?.jsonPrimitive
                        ?.contentOrNull
                        .orEmpty()
                val arguments = params?.get("arguments")?.jsonObject ?: buildJsonObject {}

                if (toolName.isBlank()) {
                    val err = JsonRpcError(code = -32602, message = "Invalid params: 'name' is required in tools/call")
                    formatErrorResponse(id, err)
                } else if (!KNOWN_TOOLS.contains(toolName)) {
                    val err = JsonRpcError(code = -32601, message = "Tool not found: $toolName")
                    formatErrorResponse(id, err)
                } else {
                    val toolResult = handleToolCall(toolName, arguments)
                    val resultJson = json.encodeToJsonElement(McpToolResult.serializer(), toolResult)
                    formatSuccessResponse(id, resultJson)
                }
            }

            else -> {
                if (isNotification) {
                    null
                } else {
                    val err = JsonRpcError(code = -32601, message = "Method not found: ${request.method}")
                    formatErrorResponse(id, err)
                }
            }
        }
    }

    private suspend fun handleToolCall(
        name: String,
        args: JsonObject,
    ): McpToolResult =
        try {
            when (name) {
                "search_music" -> {
                    handleSearchMusic(args)
                }

                "get_lyrics" -> {
                    handleGetLyrics(args)
                }

                "set_favorite" -> {
                    handleSetFavorite(args)
                }

                "play" -> {
                    handlePlay(args)
                }

                "control_playback" -> {
                    handleControlPlayback(args)
                }

                "get_playback_status" -> {
                    handleGetPlaybackStatus(args)
                }

                "get_playback_queue" -> {
                    handleGetPlaybackQueue(args)
                }

                "get_recommendations" -> {
                    handleGetRecommendations(args)
                }

                "get_playback_history" -> {
                    handleGetPlaybackHistory(args)
                }

                "get_listening_report" -> {
                    handleGetListeningReport(args)
                }

                "get_song_memory" -> {
                    handleGetSongMemory(args)
                }

                else -> {
                    McpToolResult(
                        content = listOf(McpContent(text = "Unknown tool: $name")),
                        isError = true,
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing tool: $name", e)
            McpToolResult(
                content = listOf(McpContent(text = "Execution error in $name: ${e.message}")),
                isError = true,
            )
        }

    private suspend fun handleSearchMusic(args: JsonObject): McpToolResult {
        val query = args["query"]?.jsonPrimitive?.content.orEmpty()
        if (query.isBlank()) {
            return McpToolResult(content = listOf(McpContent(text = "Parameter 'query' cannot be empty")), isError = true)
        }
        val type = args["type"]?.jsonPrimitive?.content?.lowercase() ?: "song"
        val page = args["page"]?.jsonPrimitive?.intOrNull ?: 1
        val pageSize = (args["pageSize"]?.jsonPrimitive?.intOrNull ?: 20).coerceIn(1, 50)

        val api = MusicApiService()
        return when (type) {
            "playlist" -> {
                val playlists = api.searchPlaylists(query, page, pageSize)
                val array =
                    buildJsonArray {
                        playlists.forEach { pl ->
                            add(
                                buildJsonObject {
                                    put("id", pl.tid)
                                    put("title", pl.name)
                                    put("creator", pl.creator)
                                    put("songCount", pl.songCount)
                                    put("coverUrl", pl.picUrl)
                                },
                            )
                        }
                    }
                McpToolResult(content = listOf(McpContent(text = json.encodeToString(array))))
            }

            "album" -> {
                val albums = api.searchAlbums(query, page, pageSize)
                val array =
                    buildJsonArray {
                        albums.forEach { alb ->
                            add(
                                buildJsonObject {
                                    put("albumMid", alb.mid)
                                    put("title", alb.title)
                                    put("singer", alb.artist)
                                    put("songCount", alb.songCount)
                                    put("publishDate", alb.pubTime.toString())
                                    put("coverUrl", alb.coverUrl)
                                },
                            )
                        }
                    }
                McpToolResult(content = listOf(McpContent(text = json.encodeToString(array))))
            }

            "singer", "artist" -> {
                val artists = api.searchArtists(query, page, pageSize)
                val array =
                    buildJsonArray {
                        artists.forEach { art ->
                            add(
                                buildJsonObject {
                                    put("singerMid", art.mid)
                                    put("name", art.name)
                                    put("songCount", art.songCount)
                                    put("albumCount", art.albumCount)
                                    put("avatarUrl", art.avatarUrl)
                                },
                            )
                        }
                    }
                McpToolResult(content = listOf(McpContent(text = json.encodeToString(array))))
            }

            else -> {
                val songs = api.search(query, page, pageSize)
                val array =
                    buildJsonArray {
                        songs.forEach { s ->
                            add(
                                buildJsonObject {
                                    put("songMid", s.songMid)
                                    put("songId", s.songId)
                                    put("title", s.name)
                                    put("singer", s.singer)
                                    put("album", s.album)
                                    put("durationSeconds", s.durationSeconds)
                                    put("coverUrl", s.coverUrl)
                                },
                            )
                        }
                    }
                McpToolResult(content = listOf(McpContent(text = json.encodeToString(array))))
            }
        }
    }

    private suspend fun handleGetLyrics(args: JsonObject): McpToolResult {
        val midArg = args["songMid"]?.jsonPrimitive?.content
        val songMid = if (midArg.isNullOrBlank()) getCurrentPlayingMid() else midArg

        if (songMid.isNullOrBlank()) {
            return McpToolResult(
                content = listOf(McpContent(text = "No song specified and no song is currently playing")),
                isError = true,
            )
        }

        val lines = MusicApiService().getLyrics(songMid = songMid)
        if (lines.isEmpty()) {
            return McpToolResult(content = listOf(McpContent(text = "No lyrics found for songMid: $songMid")))
        }

        val type = args["type"]?.jsonPrimitive?.content?.lowercase() ?: "original"

        if (type == "translation" && lines.all { it.transText.isNullOrBlank() }) {
            return McpToolResult(content = listOf(McpContent(text = "No translated lyrics available for songMid: $songMid")))
        }

        val hasTranslation = lines.any { !it.transText.isNullOrBlank() }

        val formattedText =
            buildString {
                lines.forEach { line ->
                    val min = (line.timestampMs / 1000) / 60
                    val sec = (line.timestampMs / 1000) % 60
                    val ms = (line.timestampMs % 1000) / 10
                    val timeTag = String.format("[%02d:%02d.%02d]", min, sec, ms)
                    when (type) {
                        "translation" -> {
                            val trans = line.transText
                            if (!trans.isNullOrBlank()) {
                                append(timeTag).append(" ").append(trans).append("\n")
                            }
                        }

                        "both" -> {
                            append(timeTag).append(" ").append(line.text)
                            if (!line.transText.isNullOrBlank()) {
                                append(" (").append(line.transText).append(")")
                            }
                            append("\n")
                        }

                        else -> {
                            append(timeTag).append(" ").append(line.text).append("\n")
                        }
                    }
                }
                if (type != "both" && type != "translation" && hasTranslation) {
                    append("\n[提示：本曲含中文翻译，传入 type=\"translation\" 仅获取翻译，或 \"both\" 获取对照]\n")
                }
            }.trimEnd()

        return McpToolResult(content = listOf(McpContent(text = formattedText)))
    }

    private suspend fun handleSetFavorite(args: JsonObject): McpToolResult {
        val favorite =
            args["favorite"]?.jsonPrimitive?.booleanOrNull
                ?: return McpToolResult(content = listOf(McpContent(text = "Parameter 'favorite' (boolean) is required")), isError = true)

        val midArg = args["songMid"]?.jsonPrimitive?.content
        val songMid = if (midArg.isNullOrBlank()) getCurrentPlayingMid() else midArg

        if (songMid.isNullOrBlank()) {
            return McpToolResult(
                content = listOf(McpContent(text = "No song specified and no song is currently playing")),
                isError = true,
            )
        }

        if (!favorite && !isDeleteAllowedProvider()) {
            return McpToolResult(
                content =
                    listOf(
                        McpContent(
                            text = "Permission denied: '允许 AI 删除内容' 门禁开关未启用，无法取消收藏。请在 Melodist 设置中开启该权限后再试。",
                        ),
                    ),
                isError = true,
            )
        }

        val api = MusicApiService()
        val resolvedId = api.resolveSongId(songMid)
        val success =
            if (favorite) {
                api.addSongToFavorite(resolvedId, songMid)
            } else {
                api.deleteSongFromFavorite(resolvedId, songMid)
            }

        return if (success) {
            val actionText = if (favorite) "收藏成功" else "已取消收藏"
            McpToolResult(content = listOf(McpContent(text = "歌曲 ($songMid) $actionText")))
        } else {
            McpToolResult(content = listOf(McpContent(text = "操作失败，可能需要登录或网络异常")), isError = true)
        }
    }

    private suspend fun handlePlay(args: JsonObject): McpToolResult {
        val rawTarget = args["target"]?.jsonPrimitive?.content?.lowercase()
        val target =
            when (rawTarget) {
                "favorite", "fav", "like" -> "favorites"
                else -> rawTarget
            } ?: return McpToolResult(content = listOf(McpContent(text = "Parameter 'target' is required")), isError = true)
        val id = args["id"]?.jsonPrimitive?.content ?: ""

        if (target in listOf("song", "playlist") && id.isBlank()) {
            return McpToolResult(content = listOf(McpContent(text = "Parameter 'id' is required when target is '$target'")), isError = true)
        }

        val result = playbackHandler.play(target, id)
        return if (result.isSuccess) {
            McpToolResult(content = listOf(McpContent(text = result.getOrNull() ?: "Playback initiated")))
        } else {
            McpToolResult(
                content = listOf(McpContent(text = "Failed to play $target: ${result.exceptionOrNull()?.message}")),
                isError = true,
            )
        }
    }

    private suspend fun handleGetRecommendations(args: JsonObject): McpToolResult {
        val rawCategory = args["category"]?.jsonPrimitive?.content?.lowercase()
        val category =
            when (rawCategory) {
                "favorite", "fav", "like" -> "favorites"
                else -> rawCategory
            } ?: return McpToolResult(content = listOf(McpContent(text = "Parameter 'category' is required")), isError = true)
        val page = args["page"]?.jsonPrimitive?.intOrNull ?: 1
        val pageSize = (args["pageSize"]?.jsonPrimitive?.intOrNull ?: 20).coerceIn(1, 50)

        if (!UserSession.isLoggedIn) {
            return McpToolResult(
                content = listOf(McpContent(text = "User is not logged in to Melodist. Please login in the app first.")),
                isError = true,
            )
        }

        val api = MusicApiService()
        var description: String? = null
        var totalCount: Int? = null
        val songs: List<Song> =
            try {
                when (category) {
                    "daily" -> {
                        val detail = api.getDailyRecommendDetail()
                        description = detail.description
                        detail.songs
                    }

                    "guess" -> {
                        api.getGuessRecommendSongs(count = pageSize)
                    }

                    "million" -> {
                        val detail = api.getMillionRecommendDetail()
                        description = detail.title
                        detail.songs
                    }

                    "favorites" -> {
                        val favDetail = api.getFavoriteSongsDetail(page = page, pageSize = pageSize)
                        totalCount = favDetail.total
                        favDetail.songs
                    }

                    else -> {
                        return McpToolResult(
                            content =
                                listOf(
                                    McpContent(
                                        text =
                                            "Unsupported recommendation category: $category. " +
                                                "Expected 'daily', 'guess', 'million', or 'favorites'",
                                    ),
                                ),
                            isError = true,
                        )
                    }
                }
            } catch (e: Exception) {
                return McpToolResult(
                    content = listOf(McpContent(text = "Failed to fetch $category recommendations: ${e.message}")),
                    isError = true,
                )
            }

        val songsJson =
            buildJsonArray {
                songs.forEach { s ->
                    add(
                        buildJsonObject {
                            put("songMid", s.songMid)
                            put("songId", s.songId)
                            put("title", s.name)
                            put("singer", s.singer)
                            put("album", s.album)
                            put("durationSeconds", s.durationSeconds)
                            put("coverUrl", s.coverUrl)
                        },
                    )
                }
            }

        val resultJson =
            buildJsonObject {
                put("category", category)
                if (totalCount != null) {
                    put("totalCount", totalCount)
                }
                if (!description.isNullOrBlank()) {
                    put("description", description)
                }
                put("count", songs.size)
                put("songs", songsJson)
            }

        return McpToolResult(content = listOf(McpContent(text = json.encodeToString(resultJson))))
    }

    private suspend fun handleGetPlaybackHistory(args: JsonObject): McpToolResult {
        val type = args["type"]?.jsonPrimitive?.content?.lowercase() ?: "song"
        val limit = args["limit"]?.jsonPrimitive?.intOrNull ?: 20
        val result = playbackHandler.getPlaybackHistory(type, limit)
        return if (result.isSuccess) {
            val array = result.getOrNull() ?: buildJsonArray {}
            McpToolResult(content = listOf(McpContent(text = json.encodeToString(array))))
        } else {
            McpToolResult(
                content = listOf(McpContent(text = "Failed to get playback history: ${result.exceptionOrNull()?.message}")),
                isError = true,
            )
        }
    }

    private suspend fun handleGetListeningReport(args: JsonObject): McpToolResult {
        if (!UserSession.isLoggedIn) {
            return McpToolResult(
                content = listOf(McpContent(text = "User is not logged in to Melodist. Please login in the app first.")),
                isError = true,
            )
        }

        val periodStr = args["period"]?.jsonPrimitive?.content?.lowercase() ?: "day"
        val date = args["date"]?.jsonPrimitive?.content
        val period =
            when (periodStr) {
                "day" -> ListeningReportPeriod.Day
                "week" -> ListeningReportPeriod.Week
                "month" -> ListeningReportPeriod.Month
                "year" -> ListeningReportPeriod.Year
                else -> ListeningReportPeriod.Day
            }

        return try {
            val report = MusicApiService().getListeningRecordReport(period, date)
            val metricFocus =
                when (report.period) {
                    ListeningReportPeriod.Day, ListeningReportPeriod.Week -> "duration"
                    ListeningReportPeriod.Month -> "song_count"
                    ListeningReportPeriod.Year -> "both"
                }
            val metricNote =
                when (report.period) {
                    ListeningReportPeriod.Day -> {
                        "日度报告官方统计重心为听歌时长（listenTimeMinutes），总首数未单独统计（为 0），单曲收听频次请参阅 rankSongs。"
                    }

                    ListeningReportPeriod.Week -> {
                        "周度报告官方统计重心为日均时长（listenTimeMinutes），总首数未单独统计（为 0），单曲收听频次请参阅 rankSongs。"
                    }

                    ListeningReportPeriod.Month -> {
                        "月度报告官方统计重心为总首数（listenSongCount），官方接口未单独统计累计总时长（为 0）。"
                    }

                    ListeningReportPeriod.Year -> {
                        "年度报告包含累计时长与累计首数宏观盘点。"
                    }
                }
            val resultObj =
                buildJsonObject {
                    put("period", report.period.label)
                    put("dateKey", report.dateKey)
                    put("title", report.title)
                    put("metricFocus", metricFocus)
                    put("metricNote", metricNote)
                    put("listenTimeMinutes", report.listenTimeSec / 60)
                    put("listenTimeSec", report.listenTimeSec)
                    put("listenSongCount", report.listenCnt)
                    put("activeHour", report.activeHour)
                    put("beatPercentage", (report.percent * 100).toInt())
                    put(
                        "topSingers",
                        buildJsonArray {
                            report.topSingers.forEach { s ->
                                add(
                                    buildJsonObject {
                                        put("rank", s.rank)
                                        put("singerName", s.singerName)
                                        put("listenTimeMinutes", s.listenTimeSec / 60)
                                        put("coverUrl", s.singerCoverUrl)
                                    },
                                )
                            }
                        },
                    )
                    put(
                        "rankSongs",
                        buildJsonArray {
                            report.rankSongs.forEach { r ->
                                add(
                                    buildJsonObject {
                                        put("rank", r.rankNo)
                                        put("title", r.song.name)
                                        put("singer", r.song.singer)
                                        put("songMid", r.song.songMid)
                                        put("listenCount", r.listenCnt)
                                    },
                                )
                            }
                        },
                    )
                    put(
                        "highlights",
                        buildJsonArray {
                            report.highlights.forEach { h ->
                                add(
                                    buildJsonObject {
                                        put("title", h.title)
                                        put("subtitle", h.subtitle)
                                        put("tips", h.tips)
                                        put("repeatCount", h.repeatCnt)
                                    },
                                )
                            }
                        },
                    )
                    report.textBlock?.let { tb ->
                        put("musicalTasteDescription", tb.text)
                    }
                    put(
                        "availableDates",
                        buildJsonArray {
                            report.availableDates.take(15).forEach { add(JsonPrimitive(it)) }
                        },
                    )
                }
            McpToolResult(content = listOf(McpContent(text = json.encodeToString(resultObj))))
        } catch (e: Exception) {
            McpToolResult(
                content = listOf(McpContent(text = "Failed to fetch listening report: ${e.message}")),
                isError = true,
            )
        }
    }

    private suspend fun handleGetSongMemory(args: JsonObject): McpToolResult {
        val midArg = args["songMid"]?.jsonPrimitive?.content
        val songMid = if (midArg.isNullOrBlank()) getCurrentPlayingMid() else midArg

        if (songMid.isNullOrBlank()) {
            return McpToolResult(
                content = listOf(McpContent(text = "No song specified and no song is currently playing")),
                isError = true,
            )
        }

        if (!UserSession.isLoggedIn) {
            return McpToolResult(content = listOf(McpContent(text = "User not logged in")), isError = true)
        }

        val includeRecords = args["includeRecords"]?.jsonPrimitive?.booleanOrNull ?: false

        return try {
            val diary = MusicApiService().getSongDiary(songMid)
            if (diary == null) {
                return McpToolResult(content = listOf(McpContent(text = "No memory diary data found for songMid: $songMid")))
            }

            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

            val resultObj =
                buildJsonObject {
                    put("songMid", diary.songMid.ifBlank { songMid })
                    put("title", diary.title)
                    put("singer", diary.subTitle)
                    put("coverUrl", diary.coverUrl)
                    put(
                        "firstListen",
                        buildJsonObject {
                            put("dateCn", diary.firstLsnTimeCn)
                            put("time", diary.firstLsnTime)
                            put("device", diary.firstLsnDevice)
                            put("daysTogether", diary.firstLsnDayCnt)
                        },
                    )
                    put(
                        "favorite",
                        buildJsonObject {
                            put("date", diary.favDate)
                            put("description", diary.favBottom)
                        },
                    )
                    put(
                        "totalPlays",
                        buildJsonObject {
                            put("countText", diary.totalLsnCountText)
                            put("description", diary.totalLsnBottom)
                            put(
                                "yearlyDistribution",
                                buildJsonArray {
                                    val years = diary.totalLsnYears
                                    val counts = diary.totalLsnCounts
                                    val minSize = minOf(years.size, counts.size)
                                    for (i in 0 until minSize) {
                                        add(
                                            buildJsonObject {
                                                put("year", years[i])
                                                put("count", counts[i])
                                            },
                                        )
                                    }
                                },
                            )
                        },
                    )
                    put(
                        "recentListen",
                        buildJsonObject {
                            put("countText", diary.recentLsnCountText)
                            put("description", diary.recentLsnBottom)
                        },
                    )
                    put(
                        "lastListen",
                        buildJsonObject {
                            put("date", diary.lastLsnDate)
                            put("time", diary.lastLsnTime)
                        },
                    )
                    if (diary.milestoneTitle.isNotBlank() || diary.milestoneTime.isNotBlank() || diary.milestoneBottom.isNotBlank()) {
                        put(
                            "milestone",
                            buildJsonObject {
                                put("title", diary.milestoneTitle)
                                put("time", diary.milestoneTime)
                                put("description", diary.milestoneBottom)
                            },
                        )
                    }
                    put("hasListenRecord", diary.hasListenRecord)

                    if (includeRecords && diary.hasListenRecord) {
                        val recordsPage = MusicApiService().getSongListenRecords(songMid)
                        put(
                            "records",
                            buildJsonArray {
                                recordsPage?.items?.forEach { item ->
                                    add(
                                        buildJsonObject {
                                            put("timestampSec", item.timestampSec)
                                            if (item.timestampSec > 0L) {
                                                val formatted = formatter.format(Instant.ofEpochSecond(item.timestampSec))
                                                put("playedAt", formatted)
                                            }
                                            put("device", item.device)
                                            put("source", item.source)
                                        },
                                    )
                                }
                            },
                        )
                    }
                }
            McpToolResult(content = listOf(McpContent(text = json.encodeToString(resultObj))))
        } catch (e: Exception) {
            McpToolResult(
                content = listOf(McpContent(text = "Failed to fetch song memory: ${e.message}")),
                isError = true,
            )
        }
    }

    private suspend fun handleControlPlayback(args: JsonObject): McpToolResult {
        val action =
            args["action"]?.jsonPrimitive?.content?.lowercase()
                ?: return McpToolResult(content = listOf(McpContent(text = "Parameter 'action' is required")), isError = true)
        val mode = args["mode"]?.jsonPrimitive?.content?.lowercase()

        val result = playbackHandler.controlPlayback(action, mode)
        return if (result.isSuccess) {
            McpToolResult(content = listOf(McpContent(text = result.getOrNull() ?: "Control action $action succeeded")))
        } else {
            McpToolResult(
                content = listOf(McpContent(text = "Control action $action failed: ${result.exceptionOrNull()?.message}")),
                isError = true,
            )
        }
    }

    private suspend fun handleGetPlaybackStatus(args: JsonObject): McpToolResult {
        val result = playbackHandler.getPlaybackStatus()
        return if (result.isSuccess) {
            val statusObj = result.getOrNull() ?: buildJsonObject {}
            McpToolResult(content = listOf(McpContent(text = json.encodeToString(statusObj))))
        } else {
            McpToolResult(
                content = listOf(McpContent(text = "Failed to get playback status: ${result.exceptionOrNull()?.message}")),
                isError = true,
            )
        }
    }

    private suspend fun handleGetPlaybackQueue(args: JsonObject): McpToolResult {
        val offset =
            args["offset"]?.jsonPrimitive?.intOrNull
                ?: args["queueOffset"]?.jsonPrimitive?.intOrNull
                ?: 0
        val limit =
            args["limit"]?.jsonPrimitive?.intOrNull
                ?: args["queueLimit"]?.jsonPrimitive?.intOrNull
                ?: 50
        val result = playbackHandler.getPlaybackQueue(offset, limit)
        return if (result.isSuccess) {
            val queueObj = result.getOrNull() ?: buildJsonObject {}
            McpToolResult(content = listOf(McpContent(text = json.encodeToString(queueObj))))
        } else {
            McpToolResult(
                content = listOf(McpContent(text = "Failed to get playback queue: ${result.exceptionOrNull()?.message}")),
                isError = true,
            )
        }
    }

    private fun buildToolsList(): JsonArray =
        buildJsonArray {
            add(
                buildJsonObject {
                    put("name", "search_music")
                    put("description", "搜索音乐曲库，支持单曲、歌单、专辑或歌手关键词检索。")
                    put(
                        "inputSchema",
                        buildJsonObject {
                            put("type", "object")
                            put(
                                "properties",
                                buildJsonObject {
                                    put(
                                        "query",
                                        buildJsonObject {
                                            put("type", "string")
                                            put("description", "搜索关键词")
                                        },
                                    )
                                    put(
                                        "type",
                                        buildJsonObject {
                                            put("type", "string")
                                            put(
                                                "enum",
                                                buildJsonArray {
                                                    add(JsonPrimitive("song"))
                                                    add(JsonPrimitive("playlist"))
                                                    add(JsonPrimitive("album"))
                                                    add(JsonPrimitive("singer"))
                                                },
                                            )
                                            put("description", "搜索类型：song（单曲，默认）、playlist（歌单）、album（专辑）或 singer（歌手）")
                                        },
                                    )
                                    put(
                                        "page",
                                        buildJsonObject {
                                            put("type", "integer")
                                            put("description", "分页页码，从 1 开始")
                                        },
                                    )
                                    put(
                                        "pageSize",
                                        buildJsonObject {
                                            put("type", "integer")
                                            put("description", "每页结果数量，默认为 20")
                                        },
                                    )
                                },
                            )
                            put("required", buildJsonArray { add(JsonPrimitive("query")) })
                        },
                    )
                },
            )

            add(
                buildJsonObject {
                    put("name", "get_lyrics")
                    put(
                        "description",
                        "获取单曲时间戳歌词。支持按需获取原文（默认）、仅中文翻译或双语对照。若省略 songMid，则默认获取当前正在播放歌曲的歌词。",
                    )
                    put(
                        "inputSchema",
                        buildJsonObject {
                            put("type", "object")
                            put(
                                "properties",
                                buildJsonObject {
                                    put(
                                        "songMid",
                                        buildJsonObject {
                                            put("type", "string")
                                            put("description", "单曲唯一标识字符串（songMid）。缺省时默认为当前播放曲目。")
                                        },
                                    )
                                    put(
                                        "type",
                                        buildJsonObject {
                                            put("type", "string")
                                            put(
                                                "enum",
                                                buildJsonArray {
                                                    add(JsonPrimitive("original"))
                                                    add(JsonPrimitive("translation"))
                                                    add(JsonPrimitive("both"))
                                                },
                                            )
                                            put(
                                                "description",
                                                "歌词内容类型：'original'（纯原文，默认，节省上下文 Token）、'translation'（仅中文翻译）或 'both'（原文与中文翻译对照混排）",
                                            )
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            )

            add(
                buildJsonObject {
                    put("name", "set_favorite")
                    put("description", "添加或取消收藏单曲（加入或移出我的喜欢）。取消收藏操作需在系统设置中开启删除权限。")
                    put(
                        "inputSchema",
                        buildJsonObject {
                            put("type", "object")
                            put(
                                "properties",
                                buildJsonObject {
                                    put(
                                        "songMid",
                                        buildJsonObject {
                                            put("type", "string")
                                            put("description", "单曲唯一标识字符串（songMid）。缺省时默认为当前播放曲目。")
                                        },
                                    )
                                    put(
                                        "favorite",
                                        buildJsonObject {
                                            put("type", "boolean")
                                            put("description", "操作指令：true 表示添加收藏，false 表示取消收藏")
                                        },
                                    )
                                },
                            )
                            put("required", buildJsonArray { add(JsonPrimitive("favorite")) })
                        },
                    )
                },
            )

            add(
                buildJsonObject {
                    put("name", "play")
                    put(
                        "description",
                        "播放音乐与点播曲库。支持播放单曲、歌单、整张专辑、歌手热门歌曲合辑、用户收藏的音乐（我的喜欢/红心歌曲）或官方推荐流（每日推荐、猜你喜欢、百万收藏精选、最近播放历史足迹）。",
                    )
                    put(
                        "inputSchema",
                        buildJsonObject {
                            put("type", "object")
                            put(
                                "properties",
                                buildJsonObject {
                                    put(
                                        "target",
                                        buildJsonObject {
                                            put("type", "string")
                                            put(
                                                "enum",
                                                buildJsonArray {
                                                    add(JsonPrimitive("song"))
                                                    add(JsonPrimitive("playlist"))
                                                    add(JsonPrimitive("album"))
                                                    add(JsonPrimitive("singer"))
                                                    add(JsonPrimitive("favorites"))
                                                    add(JsonPrimitive("daily"))
                                                    add(JsonPrimitive("guess"))
                                                    add(JsonPrimitive("million"))
                                                    add(JsonPrimitive("history"))
                                                },
                                            )
                                            put(
                                                "description",
                                                "播放目标类型：'song'（单曲）、'playlist'（歌单）、'album'（整张专辑）、'singer'（歌手热门曲目）、'favorites'（播放用户收藏的音乐/我的喜欢/红心歌曲）、'history'（历史足迹）或推荐流（'daily'每日推荐、'guess'猜你喜欢、'million'百万收藏）",
                                            )
                                        },
                                    )
                                    put(
                                        "id",
                                        buildJsonObject {
                                            put("type", "string")
                                            put("description", "目标标识符：单曲 songMid、歌单 ID、专辑 albumMid 或专辑名、歌手名或 singerMid（播放收藏音乐、推荐流及历史足迹时可省略）")
                                        },
                                    )
                                },
                            )
                            put("required", buildJsonArray { add(JsonPrimitive("target")) })
                        },
                    )
                },
            )

            add(
                buildJsonObject {
                    put("name", "get_recommendations")
                    put(
                        "description",
                        "获取个性化与官方推荐曲目列表：每日推荐、猜你喜欢、百万收藏精选或用户收藏夹。",
                    )
                    put(
                        "inputSchema",
                        buildJsonObject {
                            put("type", "object")
                            put(
                                "properties",
                                buildJsonObject {
                                    put(
                                        "category",
                                        buildJsonObject {
                                            put("type", "string")
                                            put(
                                                "enum",
                                                buildJsonArray {
                                                    add(JsonPrimitive("daily"))
                                                    add(JsonPrimitive("guess"))
                                                    add(JsonPrimitive("million"))
                                                    add(JsonPrimitive("favorites"))
                                                },
                                            )
                                            put("description", "推荐类别：'daily'（每日推荐）、'guess'（猜你喜欢）、'million'（百万收藏）或 'favorites'（我的喜欢）")
                                        },
                                    )
                                    put(
                                        "page",
                                        buildJsonObject {
                                            put("type", "integer")
                                            put("description", "分页页码（默认 1，主要用于 favorites 翻页）")
                                        },
                                    )
                                    put(
                                        "pageSize",
                                        buildJsonObject {
                                            put("type", "integer")
                                            put("description", "每页返回曲目数量（默认 20，最大 50）")
                                        },
                                    )
                                },
                            )
                            put("required", buildJsonArray { add(JsonPrimitive("category")) })
                        },
                    )
                },
            )

            add(
                buildJsonObject {
                    put("name", "get_playback_history")
                    put(
                        "description",
                        "获取单曲、专辑或歌单的历史播放足迹流水，包含精确到秒的时间戳与本地时间。",
                    )
                    put(
                        "inputSchema",
                        buildJsonObject {
                            put("type", "object")
                            put(
                                "properties",
                                buildJsonObject {
                                    put(
                                        "type",
                                        buildJsonObject {
                                            put("type", "string")
                                            put(
                                                "enum",
                                                buildJsonArray {
                                                    add(JsonPrimitive("song"))
                                                    add(JsonPrimitive("album"))
                                                    add(JsonPrimitive("playlist"))
                                                },
                                            )
                                            put("description", "足迹类型：'song'（单曲，默认）、'album'（专辑）或 'playlist'（歌单）")
                                        },
                                    )
                                    put(
                                        "limit",
                                        buildJsonObject {
                                            put("type", "integer")
                                            put("description", "返回记录最大条数（默认 20，最大 50）")
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            )

            add(
                buildJsonObject {
                    put("name", "get_listening_report")
                    put(
                        "description",
                        "获取听歌统计与周期分析报告（日/周度主打听歌时长分析，listenSongCount为0，单曲明细看rankSongs；月度主打总首数；年度为宏观时长与首数盘点）。包含常听歌手榜与音乐偏好解读。",
                    )
                    put(
                        "inputSchema",
                        buildJsonObject {
                            put("type", "object")
                            put(
                                "properties",
                                buildJsonObject {
                                    put(
                                        "period",
                                        buildJsonObject {
                                            put("type", "string")
                                            put(
                                                "enum",
                                                buildJsonArray {
                                                    add(JsonPrimitive("day"))
                                                    add(JsonPrimitive("week"))
                                                    add(JsonPrimitive("month"))
                                                    add(JsonPrimitive("year"))
                                                },
                                            )
                                            put("description", "统计周期：'day'（日，默认）、'week'（周）、'month'（月）或 'year'（年）")
                                        },
                                    )
                                    put(
                                        "date",
                                        buildJsonObject {
                                            put("type", "string")
                                            put("description", "指定统计日期字符串（如日报告 '20261121'、月报告 '202611'；缺省时获取最新周期）")
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            )

            add(
                buildJsonObject {
                    put("name", "get_song_memory")
                    put(
                        "description",
                        "获取单曲回忆日记：首次收听时间/设备、相伴天数、收藏日期、累计播放频次、历年趋势分布、最近收听及收听流水明细。若省略 songMid，则默认查询当前正在播放歌曲。",
                    )
                    put(
                        "inputSchema",
                        buildJsonObject {
                            put("type", "object")
                            put(
                                "properties",
                                buildJsonObject {
                                    put(
                                        "songMid",
                                        buildJsonObject {
                                            put("type", "string")
                                            put("description", "单曲唯一标识字符串（songMid）。缺省时默认为当前播放曲目。")
                                        },
                                    )
                                    put(
                                        "includeRecords",
                                        buildJsonObject {
                                            put("type", "boolean")
                                            put("description", "是否拉取详细的历史收听时间戳与设备流水明细（默认 false）")
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            )

            add(
                buildJsonObject {
                    put("name", "control_playback")
                    put("description", "执行基础播放控制：播放、暂停、上一首、下一首或切换循环模式。")
                    put(
                        "inputSchema",
                        buildJsonObject {
                            put("type", "object")
                            put(
                                "properties",
                                buildJsonObject {
                                    put(
                                        "action",
                                        buildJsonObject {
                                            put("type", "string")
                                            put(
                                                "enum",
                                                buildJsonArray {
                                                    add(JsonPrimitive("play"))
                                                    add(JsonPrimitive("pause"))
                                                    add(JsonPrimitive("next"))
                                                    add(JsonPrimitive("previous"))
                                                    add(JsonPrimitive("loop_mode"))
                                                },
                                            )
                                            put("description", "控制动作：'play'（播放）、'pause'（暂停）、'next'（下一首）、'previous'（上一首）或 'loop_mode'（切换循环模式）")
                                        },
                                    )
                                    put(
                                        "mode",
                                        buildJsonObject {
                                            put("type", "string")
                                            put(
                                                "enum",
                                                buildJsonArray {
                                                    add(JsonPrimitive("list_repeat"))
                                                    add(JsonPrimitive("single_repeat"))
                                                    add(JsonPrimitive("shuffle"))
                                                },
                                            )
                                            put("description", "目标循环模式（仅在 action 为 'loop_mode' 时有效）：'list_repeat'（列表循环）、'single_repeat'（单曲循环）或 'shuffle'（随机播放）")
                                        },
                                    )
                                },
                            )
                            put("required", buildJsonArray { add(JsonPrimitive("action")) })
                        },
                    )
                },
            )

            add(
                buildJsonObject {
                    put("name", "get_playback_status")
                    put(
                        "description",
                        "获取实时播放状态：当前曲目元数据、播放进度、暂停状态、循环模式及队列总曲目数。注意：若需查看接下来的待播列表或详细队列内容，请调用 get_playback_queue。",
                    )
                    put(
                        "inputSchema",
                        buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {})
                        },
                    )
                },
            )

            add(
                buildJsonObject {
                    put("name", "get_playback_queue")
                    put(
                        "description",
                        "获取当前播放器的待播列表/播放队列。仅在用户明确需要查看接下来播什么、待播列表或浏览队列内容时才按需调用。支持分页。",
                    )
                    put(
                        "inputSchema",
                        buildJsonObject {
                            put("type", "object")
                            put(
                                "properties",
                                buildJsonObject {
                                    put(
                                        "offset",
                                        buildJsonObject {
                                            put("type", "integer")
                                            put("description", "播放队列切片起始下标（默认 0）")
                                        },
                                    )
                                    put(
                                        "limit",
                                        buildJsonObject {
                                            put("type", "integer")
                                            put("description", "返回的队列项数量（默认 50，最大 100）")
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            )
        }

    companion object {
        private const val TAG = "McpDispatcher"
        private val KNOWN_TOOLS =
            setOf(
                "search_music",
                "get_lyrics",
                "set_favorite",
                "play",
                "get_recommendations",
                "get_playback_history",
                "get_listening_report",
                "get_song_memory",
                "control_playback",
                "get_playback_status",
                "get_playback_queue",
            )
    }
}
