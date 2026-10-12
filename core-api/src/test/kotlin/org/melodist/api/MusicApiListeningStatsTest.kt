package org.melodist.api

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.melodist.model.ListeningReportPeriod

class MusicApiListeningStatsTest {
    @Test
    fun `parseListeningRecordReport correctly extracts daily metrics and rank songs`() {
        val jsonSample =
            """
            {
              "code": 0,
              "req_bar": {
                "code": 0,
                "data": {
                  "availableDates": ["20261012", "20261011"],
                  "today": {
                    "title": "今日听歌时长",
                    "listenTime": {
                      "listenTimeSec": 1500,
                      "activeHour": 13
                    },
                    "chart": {
                      "chartInfo": {
                        "13": { "value": 1500, "avgValue": 50 }
                      }
                    },
                    "singers": [
                      {
                        "id": 8922907,
                        "name": "NOMELON NOLEMON",
                        "cover": "https://y.gtimg.cn/sample.jpg",
                        "listenTimeSec": 300
                      }
                    ]
                  }
                }
              },
              "req_rank": {
                "code": 0,
                "data": {
                  "rank": [
                    {
                      "listenCnt": 3,
                      "track": {
                        "id": 578521842,
                        "mid": "004HIVgI0S151I",
                        "name": "きえない",
                        "singer": [{ "id": 8922907, "mid": "000CB3R940UJJW", "name": "NOMELON NOLEMON" }],
                        "album": { "id": 67472626, "mid": "003Z0Mrk3zcYRq", "name": "きえない" }
                      }
                    }
                  ]
                }
              }
            }
            """.trimIndent()

        val result = parseListeningRecordReport(jsonSample, ListeningReportPeriod.Day, "20261012")

        assertEquals(ListeningReportPeriod.Day, result.period)
        assertEquals("20261012", result.dateKey)
        assertEquals("今日听歌时长", result.title)
        assertEquals(1500L, result.listenTimeSec)
        assertEquals(13, result.activeHour)
        assertEquals(listOf("20261012", "20261011"), result.availableDates)

        assertEquals(1, result.topSingers.size)
        assertEquals("NOMELON NOLEMON", result.topSingers[0].singerName)
        assertEquals(300L, result.topSingers[0].listenTimeSec)

        assertEquals(1, result.rankSongs.size)
        assertEquals("きえない", result.rankSongs[0].song.name)
        assertEquals(578521842L, result.rankSongs[0].song.songId)
        assertEquals(3, result.rankSongs[0].listenCnt)
        assertEquals(1, result.rankSongs[0].rankNo)
    }

    @Test
    fun `parseListeningRecordReport correctly extracts weekly metrics and chart points without crashing on nulls`() {
        val jsonWeek =
            """
            {
              "code": 0,
              "req_bar": {
                "code": 0,
                "data": {
                  "availableDates": ["20261012", "20261005"],
                  "today": { "title": "", "chart": { "chartInfo": null } },
                  "week": {
                    "title": "本周日均",
                    "listenTime": {
                      "listenTimeSec": 2530,
                      "percent": 1.0,
                      "upOrDown": 1
                    },
                    "chart": {
                      "chartInfo": {
                        "0": { "value": 0, "avgValue": 0 },
                        "1": { "value": 2530, "avgValue": 506 }
                      }
                    }
                  },
                  "optionResults": [
                    { "id": 8922907, "name": "NOMELON NOLEMON", "value": 330, "singerAvatar": "https://img.jpg" }
                  ],
                  "textBlock": {
                    "text": "最近听歌频繁出现世界",
                    "colorWords": ["世界"]
                  }
                }
              },
              "req_rank": {
                "code": 0,
                "data": { "rank": [] }
              }
            }
            """.trimIndent()

        val result = parseListeningRecordReport(jsonWeek, ListeningReportPeriod.Week, "20261012")

        assertEquals(ListeningReportPeriod.Week, result.period)
        assertEquals("本周日均", result.title)
        assertEquals(2530L, result.listenTimeSec)
        assertEquals(1.0f, result.percent)
        assertEquals(1, result.upOrDown)
        assertEquals(7, result.chartPoints.size)
        assertEquals("一", result.chartPoints[0].text)
        assertEquals(2530L, result.chartPoints[0].value)
        assertEquals(1, result.topSingers.size)
        assertNotNull(result.textBlock)
        assertEquals("最近听歌频繁出现世界", result.textBlock?.text)
    }

    @Test
    fun `parseListeningRecordReport correctly extracts monthly metrics, highlights and trend points`() {
        val jsonMonth =
            """
            {
              "code": 0,
              "req_bar": {
                "code": 0,
                "data": {
                  "availableDates": ["202610", "202609"],
                  "listenTime": {
                    "listenCnt": 14,
                    "percent": 0.83,
                    "upOrDown": 2
                  },
                  "bar": {
                    "optionResults": {
                      "202609": [{ "id": 9, "value": 83, "singerAvatar": "" }],
                      "202610": [{ "id": 10, "value": 14, "singerAvatar": "https://avatar.jpg" }]
                    }
                  },
                  "highLights": [
                    {
                      "type": 5,
                      "title": "与你相伴",
                      "subtitle": "13位歌手",
                      "tips": "路上或没有更美的邂逅",
                      "iconsUrl": ["https://icon1.jpg"]
                    }
                  ]
                }
              },
              "req_rank": {
                "code": 0,
                "data": { "rank": [] }
              }
            }
            """.trimIndent()

        val result = parseListeningRecordReport(jsonMonth, ListeningReportPeriod.Month, "202610")

        assertEquals(ListeningReportPeriod.Month, result.period)
        assertEquals("10月听歌首数", result.title)
        assertEquals(14, result.listenCnt)
        assertEquals(0.83f, result.percent)
        assertEquals(2, result.chartPoints.size)
        assertEquals("9月", result.chartPoints[0].text)
        assertEquals(83L, result.chartPoints[0].value)
        assertEquals("10月", result.chartPoints[1].text)
        assertEquals(14L, result.chartPoints[1].value)
        assertEquals("https://avatar.jpg", result.chartPoints[1].avatarUrl)
        assertEquals(1, result.highlights.size)
        assertEquals("与你相伴", result.highlights[0].title)
        assertEquals("13位歌手", result.highlights[0].subtitle)
    }

    @Test
    fun `parseListeningRecordReport correctly extracts yearly metrics and highlights`() {
        val jsonYear =
            """
            {
              "code": 0,
              "req_bar": {
                "code": 0,
                "data": {
                  "availableDates": ["2025", "2024"],
                  "listenTime": {
                    "listenTimeSec": 1154965,
                    "listenCnt": 3861
                  },
                  "bar": {
                    "optionResults": {
                      "2024": [{ "id": 2024, "value": 0 }],
                      "2025": [{ "id": 2025, "value": 3861, "singerAvatar": "https://avatar.jpg" }]
                    }
                  },
                  "highLights": [
                    {
                      "type": 7,
                      "title": "2025",
                      "subtitle": "年度盘点",
                      "tips": "重温我的2025年"
                    }
                  ]
                }
              },
              "req_rank": {
                "code": 0,
                "data": { "rank": [] }
              }
            }
            """.trimIndent()

        val result = parseListeningRecordReport(jsonYear, ListeningReportPeriod.Year, "2025")

        assertEquals(ListeningReportPeriod.Year, result.period)
        assertEquals("年度听歌时长", result.title)
        assertEquals(1154965L, result.listenTimeSec)
        assertEquals(3861, result.listenCnt)
        assertEquals(2, result.chartPoints.size)
        assertEquals("2024", result.chartPoints[0].text)
        assertEquals(0L, result.chartPoints[0].value)
        assertEquals("2025", result.chartPoints[1].text)
        assertEquals(3861L, result.chartPoints[1].value)
        assertEquals(1, result.highlights.size)
        assertEquals("2025", result.highlights[0].title)
        assertEquals("年度盘点", result.highlights[0].subtitle)
    }
}
