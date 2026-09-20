package org.melodist.api

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.melodist.model.CommentPage
import org.melodist.model.SongComment

class MusicApiCommentTest {
    @Test
    fun `parseCommentElement correctly extracts fields from standard comment json`() {
        val rawJson =
            """
            {
              "avatarurl": "https://thirdwx.qlogo.cn/avatar123",
              "commentid": "cmt_1001",
              "nick": "测试听友",
              "praisenum": 999,
              "rootcommentcontent": "这首歌的前奏太绝了！",
              "time": 1700000000
            }
            """.trimIndent()
        val element = Json.parseToJsonElement(rawJson)
        val comment = parseCommentElement(element, isHot = true)

        assertNotNull(comment)
        assertEquals("cmt_1001", comment?.commentId)
        assertEquals("测试听友", comment?.nick)
        assertEquals("https://thirdwx.qlogo.cn/avatar123", comment?.avatarUrl)
        assertEquals("这首歌的前奏太绝了！", comment?.content)
        assertEquals(999, comment?.praiseNum)
        assertEquals(1700000000L, comment?.timeSec)
        assertTrue(comment?.isHot == true)
    }

    @Test
    fun `parseCommentElement falls back to alternative fields and handles empty nickname`() {
        val rawJson =
            """
            {
              "rootcommentid": "root_2002",
              "rootcommentnick": "@另外一个听众",
              "commentcontent": "深夜循环中",
              "time": 1710000000
            }
            """.trimIndent()
        val element = Json.parseToJsonElement(rawJson)
        val comment = parseCommentElement(element, isHot = false)

        assertNotNull(comment)
        assertEquals("root_2002", comment?.commentId)
        assertEquals("另外一个听众", comment?.nick)
        assertEquals("深夜循环中", comment?.content)
        assertEquals(0, comment?.praiseNum)
        assertFalse(comment?.isHot == true)
    }

    @Test
    fun `parseCommentElement returns null when comment content is blank`() {
        val rawJson =
            """
            {
              "commentid": "cmt_empty",
              "nick": "路人甲",
              "rootcommentcontent": "   "
            }
            """.trimIndent()
        val element = Json.parseToJsonElement(rawJson)
        val comment = parseCommentElement(element, isHot = false)
        assertNull(comment)
    }

    @Test
    fun `commentPage model holds totalCount and separates hot and normal comments`() {
        val hotComment =
            SongComment(
                commentId = "h1",
                nick = "热评用户",
                content = "高赞好评",
                isHot = true,
            )
        val normalComment =
            SongComment(
                commentId = "n1",
                nick = "普通用户",
                content = "刚刚听完",
                isHot = false,
            )

        val page =
            CommentPage(
                totalCount = 1000,
                hotComments = listOf(hotComment),
                comments = listOf(normalComment),
                hasMore = true,
            )

        assertEquals(1000, page.totalCount)
        assertEquals(1, page.hotComments.size)
        assertEquals(1, page.comments.size)
        assertTrue(page.hasMore)
        assertTrue(page.hotComments.first().isHot)
        assertFalse(page.comments.first().isHot)
    }
}
