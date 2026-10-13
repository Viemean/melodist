package org.melodist.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.melodist.model.CommentPage
import org.melodist.model.SongComment

/**
 * 歌曲评论区 API 扩展
 */
suspend fun MusicApiService.getSongComments(
    songId: Long = 0L,
    songMid: String = "",
    pageNum: Int = 0,
    pageSize: Int = 25,
    lastCommentSeqNo: String = "",
): CommentPage? =
    withContext(Dispatchers.IO) {
        val actualId =
            if (songId > 0L) {
                songId
            } else if (songMid.isNotBlank()) {
                resolveSongId(songMid)
            } else {
                0L
            }
        if (actualId <= 0L) return@withContext null

        val page = pageNum.coerceAtLeast(0)
        val size = pageSize.coerceIn(1, 50)

        // 优先使用支持图片富媒体与属地的现代网关接口
        val modernResult = fetchModernComments(actualId, page, size, lastCommentSeqNo)
        if (modernResult != null) return@withContext modernResult

        // 降级使用传统全局 H5 评论接口
        fetchLegacyComments(actualId, page, size)
    }

private suspend fun MusicApiService.fetchModernComments(
    songId: Long,
    page: Int,
    size: Int,
    lastCommentSeqNo: String = "",
): CommentPage? {
    val payload =
        """
        {
            "comm": {
                "cv": 4747474,
                "ct": 24,
                "format": "json",
                "inCharset": "utf-8",
                "outCharset": "utf-8",
                "notice": 0,
                "platform": "yqq.json",
                "needNewCode": 1,
                "uin": 0
            },
            "req": {
                "module": "music.globalComment.CommentRead",
                "method": "GetNewCommentList",
                "param": {
                    "BizType": 1,
                    "BizId": "$songId",
                    "LastCommentSeqNo": "$lastCommentSeqNo",
                    "PageSize": $size,
                    "PageNum": $page,
                    "FromCommentId": "",
                    "WithHot": ${if (page == 0) 1 else 0},
                    "PicEnable": 1
                }
            }
        }
        """.trimIndent()

    return try {
        val respJson = postGateway(payload)
        val root = Json.parseToJsonElement(respJson).jsonObject
        val reqObj = root["req"]?.jsonObject ?: return null
        val code = reqObj["code"]?.jsonPrimitive?.intOrNull ?: -1
        if (code != 0) return null

        val dataObj = reqObj["data"]?.jsonObject ?: return null
        val totalCount =
            dataObj["TotalCmNum"]?.jsonPrimitive?.intOrNull
                ?: dataObj["CommentList"]
                    ?.jsonObject
                    ?.get("Total")
                    ?.jsonPrimitive
                    ?.intOrNull
                ?: 0

        val hotList = mutableListOf<SongComment>()
        if (page == 0) {
            val seenHotIds = mutableSetOf<String>()
            listOf("CommentList3", "CommentList2").forEach { key ->
                dataObj[key]?.jsonObject?.get("Comments")?.jsonArray?.forEach { elem ->
                    parseModernCommentElement(elem, isHot = true)?.let { comment ->
                        if (seenHotIds.add(comment.commentId)) {
                            hotList.add(comment)
                        }
                    }
                }
            }
        }

        val normalList = mutableListOf<SongComment>()
        val commentListObj = dataObj["CommentList"]?.jsonObject
        commentListObj?.get("Comments")?.jsonArray?.forEach { elem ->
            parseModernCommentElement(elem, isHot = false)?.let { normalList.add(it) }
        }

        val serverHasMore = commentListObj?.get("HasMore")?.jsonPrimitive?.intOrNull == 1
        val hasMore = serverHasMore && normalList.isNotEmpty()
        val lastSeqNo =
            normalList.lastOrNull { it.seqNo.isNotBlank() }?.seqNo
                ?: commentListObj
                    ?.get("Comments")
                    ?.jsonArray
                    ?.lastOrNull()
                    ?.jsonObject
                    ?.get("SeqNo")
                    ?.jsonPrimitive
                    ?.contentOrNull
                    .orEmpty()

        CommentPage(
            totalCount = totalCount,
            hotComments = hotList,
            comments = normalList,
            hasMore = hasMore,
            lastSeqNo = lastSeqNo,
        )
    } catch (_: Exception) {
        null
    }
}

private suspend fun MusicApiService.fetchLegacyComments(
    songId: Long,
    page: Int,
    size: Int,
): CommentPage? {
    val url =
        "https://c.y.qq.com/base/fcgi-bin/fcg_global_comment_h5.fcg?biztype=1&topid=$songId&cmd=8&pagenum=$page&pagesize=$size"

    return try {
        val respJson = getUrl(url)
        val root = Json.parseToJsonElement(respJson).jsonObject
        val code = root["code"]?.jsonPrimitive?.intOrNull ?: -1
        if (code != 0) return null

        val commentObj = root["comment"]?.jsonObject
        val hotCommentObj = root["hot_comment"]?.jsonObject

        val totalCount =
            commentObj?.get("commenttotal")?.jsonPrimitive?.intOrNull
                ?: root["commenttotal"]?.jsonPrimitive?.intOrNull ?: 0
        val moreComment = root["morecomment"]?.jsonPrimitive?.intOrNull ?: 0

        val hotList = mutableListOf<SongComment>()
        hotCommentObj?.get("commentlist")?.jsonArray?.forEach { elem ->
            parseCommentElement(elem, isHot = true)?.let { hotList.add(it) }
        }

        val normalList = mutableListOf<SongComment>()
        commentObj?.get("commentlist")?.jsonArray?.forEach { elem ->
            parseCommentElement(elem, isHot = false)?.let { normalList.add(it) }
        }

        val hasMore = moreComment == 1 && normalList.size >= size

        CommentPage(
            totalCount = totalCount,
            hotComments = hotList,
            comments = normalList,
            hasMore = hasMore,
        )
    } catch (_: Exception) {
        null
    }
}

/**
 * 解析现代版评论 JSON 节点并转换为 [SongComment] 模型。
 *
 * @param element 评论 JSON 节点
 * @param isHot 是否标记为热评
 * @return 构造成功的 [SongComment] 实例；当节点格式非法或正文为空时返回 `null`
 */
internal fun parseModernCommentElement(
    element: JsonElement,
    isHot: Boolean,
): SongComment? {
    val obj =
        try {
            element.jsonObject
        } catch (_: Exception) {
            return null
        }
    val seqNo = obj["SeqNo"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val rawCmId = obj["CmId"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val commentId = rawCmId.ifBlank { seqNo }
    val nick =
        obj["Nick"]
            ?.jsonPrimitive
            ?.contentOrNull
            .orEmpty()
            .removePrefix("@")
    val avatarUrl = obj["Avatar"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val rawContent = obj["Content"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val content = decodeHtmlEntities(rawContent)
    if (content.isBlank()) return null

    val timeSec = obj["PubTime"]?.jsonPrimitive?.longOrNull ?: 0L
    val praiseNum = obj["PraiseNum"]?.jsonPrimitive?.intOrNull ?: 0
    val isPraised =
        obj["IsPraise"]?.jsonPrimitive?.intOrNull == 1 ||
            obj["is_praised"]?.jsonPrimitive?.intOrNull == 1 ||
            obj["IsPraised"]?.jsonPrimitive?.intOrNull == 1 ||
            obj["PraiseStatus"]?.jsonPrimitive?.intOrNull == 1
    val picUrl = obj["Pic"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val picSize = obj["PicSize"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val location = obj["Location"]?.jsonPrimitive?.contentOrNull.orEmpty()

    return SongComment(
        commentId = commentId,
        nick = nick.ifBlank { "匿名用户" },
        avatarUrl = avatarUrl,
        content = content,
        timeSec = timeSec,
        praiseNum = praiseNum,
        isHot = isHot,
        picUrl = picUrl,
        picSize = picSize,
        location = location,
        seqNo = seqNo,
        isPraised = isPraised,
    )
}

/**
 * 将评论文本中的常用 HTML 转义字符解码为纯文本字符。
 *
 * @param text 包含 HTML 实体的原始字符串
 * @return 解码并去除两端首尾空白后的文本字符串
 */
internal fun decodeHtmlEntities(text: String): String {
    if (text.isBlank()) return ""
    return text
        .replace("&nbsp;", " ")
        .replace("&#13;", "\n")
        .replace("&#10;", "\n")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
        .trim()
}

/**
 * 解析传统版评论 JSON 节点并转换为 [SongComment] 模型。
 *
 * @param element 评论 JSON 节点
 * @param isHot 是否标记为热评
 * @return 构造成功的 [SongComment] 实例；当节点格式非法或正文为空时返回 `null`
 */
internal fun parseCommentElement(
    element: JsonElement,
    isHot: Boolean,
): SongComment? {
    val obj =
        try {
            element.jsonObject
        } catch (_: Exception) {
            return null
        }
    val commentId =
        obj["commentid"]?.jsonPrimitive?.contentOrNull
            ?: obj["rootcommentid"]?.jsonPrimitive?.contentOrNull ?: ""
    val nick =
        obj["nick"]?.jsonPrimitive?.contentOrNull
            ?: obj["rootcommentnick"]
                ?.jsonPrimitive
                ?.contentOrNull
                .orEmpty()
                .removePrefix("@")
    val avatarUrl = obj["avatarurl"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val content =
        obj["rootcommentcontent"]?.jsonPrimitive?.contentOrNull
            ?: obj["commentcontent"]?.jsonPrimitive?.contentOrNull
            ?: obj["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
    if (content.isBlank()) return null

    val timeSec = obj["time"]?.jsonPrimitive?.longOrNull ?: 0L
    val praiseNum = obj["praisenum"]?.jsonPrimitive?.intOrNull ?: 0
    val isPraised =
        obj["ispraise"]?.jsonPrimitive?.intOrNull == 1 ||
            obj["is_praised"]?.jsonPrimitive?.intOrNull == 1

    return SongComment(
        commentId = commentId,
        nick = nick.ifBlank { "匿名用户" },
        avatarUrl = avatarUrl,
        content = content,
        timeSec = timeSec,
        praiseNum = praiseNum,
        isHot = isHot,
        isPraised = isPraised,
    )
}

/**
 * 歌曲评论数全局内存缓存（会话级 LRU 缓存，加速长按菜单与详情页即时展示）。
 */
object SongCommentCountCache {
    private val cache = java.util.concurrent.ConcurrentHashMap<Long, Int>()

    fun get(songId: Long): Int? = cache[songId]

    fun put(songId: Long, count: Int) {
        if (songId > 0L && count >= 0) {
            cache[songId] = count
        }
    }

    fun clear() {
        cache.clear()
    }
}

/**
 * 轻量获取单曲的评论总数（仅拉取统计数值，不携带任何评论列表内容）。
 *
 * @param songId 歌曲数字 ID
 * @param forceRefresh 是否强制忽略缓存拉取远端
 * @return 评论总数，若失败或不存在则返回 null
 */
suspend fun MusicApiService.getSongCommentCount(songId: Long, forceRefresh: Boolean = false): Int? =
    withContext(Dispatchers.IO) {
        if (songId <= 0L) return@withContext null
        if (!forceRefresh) {
            val cached = SongCommentCountCache.get(songId)
            if (cached != null) return@withContext cached
        }
        val payload =
            """
            {
                "comm": {
                    "cv": 4747474,
                    "ct": 24,
                    "format": "json",
                    "platform": "yqq.json",
                    "uin": 0
                },
                "req": {
                    "module": "music.globalComment.CommentRead",
                    "method": "GetCommentCount",
                    "param": {
                        "request_list": [
                            {
                                "biz_type": 1,
                                "biz_id": "$songId"
                            }
                        ]
                    }
                }
            }
            """.trimIndent()

        try {
            val respJson = postGateway(payload)
            val count = parseCommentCountResponse(respJson)
            if (count != null) {
                SongCommentCountCache.put(songId, count)
            }
            count
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            ApiLogger.w("MusicApiComment", "getSongCommentCount failed for songId=$songId", e)
            null
        }
    }

/**
 * 解析评论计数网关响应 JSON。
 */
internal fun parseCommentCountResponse(responseStr: String): Int? {
    return try {
        val root = Json.parseToJsonElement(responseStr).jsonObject
        val reqObj = root["req"]?.jsonObject ?: return null
        val code = reqObj["code"]?.jsonPrimitive?.intOrNull ?: -1
        if (code != 0) return null

        val dataObj = reqObj["data"]?.jsonObject ?: return null
        val responseList = dataObj["response_list"]?.jsonArray
        val firstItem = responseList?.firstOrNull()?.jsonObject
        firstItem?.get("count")?.jsonPrimitive?.intOrNull
    } catch (_: Exception) {
        null
    }
}

/**
 * 发送歌曲评论或回复已有评论。
 *
 * @param songId 歌曲数字 ID
 * @param content 评论文本内容
 * @param replyCommentId 若为回复评论则传入目标评论 ID，若发表主评论则为 null
 * @return 成功返回新生成的评论 ID，失败返回异常 Result
 */
suspend fun MusicApiService.sendSongComment(
    songId: Long,
    content: String,
    replyCommentId: String? = null,
): Result<String> =
    withContext(Dispatchers.IO) {
        if (songId <= 0L) {
            return@withContext Result.failure(IllegalArgumentException("Invalid songId: $songId"))
        }
        val trimmed = content.trim()
        if (trimmed.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("评论内容不能为空"))
        }

        val uin = getEffectiveUin()
        val numericUin = uin.toLongOrNull() ?: 0L
        val musicKey = UserSession.profile.musicKey

        val payload =
            buildJsonObject {
                put(
                    "comm",
                    buildJsonObject {
                        put("cv", 4747474)
                        put("ct", 24)
                        put("format", "json")
                        put("inCharset", "utf-8")
                        put("outCharset", "utf-8")
                        put("notice", 0)
                        put("platform", "yqq.json")
                        put("needNewCode", 1)
                        put("uin", numericUin)
                        if (musicKey.isNotBlank()) {
                            put("authst", musicKey)
                        }
                    },
                )
                put(
                    "req",
                    buildJsonObject {
                        put("module", "music.globalComment.CommentWriteServer")
                        put("method", "AddComment")
                        put(
                            "param",
                            buildJsonObject {
                                put("BizType", 1)
                                put("BizId", songId.toString())
                                put("Content", trimmed)
                                put("RepliedCmId", replyCommentId.orEmpty())
                            },
                        )
                    },
                )
            }.toString()

        try {
            val respJson = postGateway(payload)
            val root = Json.parseToJsonElement(respJson).jsonObject
            val reqObj = root["req"]?.jsonObject ?: return@withContext Result.failure(IllegalStateException("返回数据为空"))
            val code = reqObj["code"]?.jsonPrimitive?.intOrNull ?: -1
            if (code != 0) {
                val msg =
                    reqObj["msg"]?.jsonPrimitive?.contentOrNull
                        ?: reqObj["subcode"]?.jsonPrimitive?.contentOrNull
                        ?: "发布失败 (code: $code)"
                return@withContext Result.failure(IllegalStateException(msg))
            }

            val dataObj = reqObj["data"]?.jsonObject
            val addedCmId =
                dataObj?.get("AddedCmId")?.jsonPrimitive?.contentOrNull
                    ?: dataObj?.get("CmId")?.jsonPrimitive?.contentOrNull
                    ?: System.currentTimeMillis().toString()
            Result.success(addedCmId)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.failure(e)
        }
    }

/**
 * 点赞或取消点赞评论。
 *
 * @param songId 歌曲数字 ID
 * @param commentId 目标评论 ID
 * @param isPraise true 为点赞，false 为取消点赞
 * @return 成功返回 Result.success(Unit)，失败返回异常 Result
 */
suspend fun MusicApiService.praiseSongComment(
    songId: Long,
    commentId: String,
    isPraise: Boolean,
): Result<Unit> =
    withContext(Dispatchers.IO) {
        if (songId <= 0L || commentId.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("参数错误"))
        }
        val cmd = if (isPraise) 1 else 2
        val url =
            "https://c.y.qq.com/base/fcgi-bin/fcg_global_comment_praise_h5.fcg?cmd=$cmd&reqtype=2&biztype=1&topid=$songId&commentid=$commentId"

        try {
            val respJson = getUrl(url)
            val root = Json.parseToJsonElement(respJson).jsonObject
            val code = root["code"]?.jsonPrimitive?.intOrNull ?: -1
            if (code == 0) {
                Result.success(Unit)
            } else {
                val msg = root["msg"]?.jsonPrimitive?.contentOrNull ?: "操作失败 (code: $code)"
                Result.failure(IllegalStateException(msg))
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.failure(e)
        }
    }

