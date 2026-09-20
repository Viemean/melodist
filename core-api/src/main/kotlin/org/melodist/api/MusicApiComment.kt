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
        val modernResult = fetchModernComments(actualId, page, size)
        if (modernResult != null) return@withContext modernResult

        // 降级使用传统全局 H5 评论接口
        fetchLegacyComments(actualId, page, size)
    }

private suspend fun MusicApiService.fetchModernComments(
    songId: Long,
    page: Int,
    size: Int,
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
                    "LastCommentSeqNo": "",
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
                ?: dataObj["CommentList"]?.jsonObject?.get("Total")?.jsonPrimitive?.intOrNull
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

        val hasMore = (commentListObj?.get("HasMore")?.jsonPrimitive?.intOrNull == 1) || (normalList.size >= size)

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
    val commentId = obj["CmId"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val nick = obj["Nick"]?.jsonPrimitive?.contentOrNull.orEmpty().removePrefix("@")
    val avatarUrl = obj["Avatar"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val rawContent = obj["Content"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val content = decodeHtmlEntities(rawContent)
    if (content.isBlank()) return null

    val timeSec = obj["PubTime"]?.jsonPrimitive?.longOrNull ?: 0L
    val praiseNum = obj["PraiseNum"]?.jsonPrimitive?.intOrNull ?: 0
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
    )
}

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
            ?: obj["rootcommentnick"]?.jsonPrimitive?.contentOrNull.orEmpty().removePrefix("@")
    val avatarUrl = obj["avatarurl"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val content =
        obj["rootcommentcontent"]?.jsonPrimitive?.contentOrNull
            ?: obj["commentcontent"]?.jsonPrimitive?.contentOrNull
            ?: obj["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
    if (content.isBlank()) return null

    val timeSec = obj["time"]?.jsonPrimitive?.longOrNull ?: 0L
    val praiseNum = obj["praisenum"]?.jsonPrimitive?.intOrNull ?: 0

    return SongComment(
        commentId = commentId,
        nick = nick.ifBlank { "匿名用户" },
        avatarUrl = avatarUrl,
        content = content,
        timeSec = timeSec,
        praiseNum = praiseNum,
        isHot = isHot,
    )
}
