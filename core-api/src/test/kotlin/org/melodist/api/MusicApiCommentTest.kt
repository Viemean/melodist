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

    @Test
    fun `parseModernCommentElement correctly extracts picture, size and location`() {
        val rawJson =
            """
            {
              "Avatar": "https://thirdwx.qlogo.cn/avatar_pic",
              "CmId": "mod_cmt_9001",
              "SeqNo": "1683661934034981888",
              "Nick": "☘️みどりお",
              "Content": "谁还记得他只是一个16岁的少女？",
              "PubTime": 1712822400,
              "PraiseNum": 520,
              "Pic": "https://music-file.y.qq.com/comment/u/test/5f805198.jpeg",
              "PicSize": "900x1440",
              "Location": "广东"
            }
            """.trimIndent()
        val element = Json.parseToJsonElement(rawJson)
        val comment = parseModernCommentElement(element, isHot = true)

        assertNotNull(comment)
        assertEquals("mod_cmt_9001", comment?.commentId)
        assertEquals("1683661934034981888", comment?.seqNo)
        assertEquals("☘️みどりお", comment?.nick)
        assertEquals("https://thirdwx.qlogo.cn/avatar_pic", comment?.avatarUrl)
        assertEquals("谁还记得他只是一个16岁的少女？", comment?.content)
        assertEquals(1712822400L, comment?.timeSec)
        assertEquals(520, comment?.praiseNum)
        assertTrue(comment?.isHot == true)
        assertEquals("https://music-file.y.qq.com/comment/u/test/5f805198.jpeg", comment?.picUrl)
        assertEquals("900x1440", comment?.picSize)
        assertEquals("广东", comment?.location)
    }

    @Test
    fun `parseModernCommentElement falls back to SeqNo when CmId is blank`() {
        val rawJson =
            """
            {
              "SeqNo": "1777999888111",
              "Nick": "匿名听友",
              "Content": "好听"
            }
            """.trimIndent()
        val element = Json.parseToJsonElement(rawJson)
        val comment = parseModernCommentElement(element, isHot = false)

        assertNotNull(comment)
        assertEquals("1777999888111", comment?.commentId)
        assertEquals("1777999888111", comment?.seqNo)
    }

    @Test
    fun `decodeHtmlEntities unescapes common HTML entities correctly`() {
        val raw = "&quot;Hello&quot;&nbsp;&amp;&nbsp;&lt;World&gt;&#13;Line2"
        val decoded = decodeHtmlEntities(raw)
        assertEquals("\"Hello\" & <World>\nLine2", decoded)
    }
}
