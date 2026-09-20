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
        val url =
            "https://c.y.qq.com/base/fcgi-bin/fcg_global_comment_h5.fcg?biztype=1&topid=$actualId&cmd=8&pagenum=$page&pagesize=$size"

        try {
            val respJson = getUrl(url)
            val root = Json.parseToJsonElement(respJson).jsonObject
            val code = root["code"]?.jsonPrimitive?.intOrNull ?: -1
            if (code != 0) return@withContext null

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
