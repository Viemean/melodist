package org.melodist.model

import java.io.File

/**
 * 封面应用场景枚举
 */
enum class CoverScenario {
    /**
     * 列表 / 抽屉缩略图：固定为 500x500，低显存消耗，保证高帧率滑动
     */
    THUMBNAIL,

    /**
     * 歌单 / 专辑详情页头部封面：1200x1200 -> 800x800 -> 500x500
     */
    DETAIL,

    /**
     * 播放界面卡片与走马灯：WiFi 下 1200x1200 优先，蜂窝移动网络下降级为 800x800；本地音频优先加载关联原画
     */
    PLAYER,

    /**
     * 全屏大图查看器：优先加载无损母带级原始大图，支持双指放大查看细节
     */
    FULLSCREEN_RAW,
}

/**
 * 全局统一封面清晰度与路径解析引擎。
 * 消除散落于各处的正则替换与硬编码。
 */
object CoverUrlResolver {
    private val REGEX_RESOLUTION = Regex("R[0-9]+x[0-9]+")
    private val REGEX_T002 = Regex("T002R\\d+x\\d+M000")
    private val REGEX_T062 = Regex("T062R\\d+x\\d+M000")

    private const val DIMENSION_THUMBNAIL = 500
    private const val DIMENSION_PLAYER = 1200
    private const val DIMENSION_FALLBACK = 800

    /**
     * 将指定在线封面链接替换为指定分辨率（例如 500 -> R500x500）
     */
    fun replaceDimension(url: String, dimension: Int): String {
        if (url.isBlank()) return ""
        return if (url.contains(REGEX_RESOLUTION)) {
            url.replace(REGEX_RESOLUTION, "R${dimension}x${dimension}")
        } else {
            url
        }
    }

    /**
     * 提取在线 CDN 封面无分辨率后缀的母版原始大图链接（例如去除 R1200x1200）
     */
    fun extractRawCdnUrl(url: String): String? {
        if (url.isBlank()) return null
        if (url.startsWith("/") || url.startsWith("file://")) return null
        return if (url.contains(REGEX_RESOLUTION)) {
            url.replace(REGEX_RESOLUTION, "")
        } else {
            null
        }
    }

    /**
     * 获取通用的 500x500 缩略图直链
     */
    fun getThumbnailUrl(url: String): String {
        if (url.isBlank()) return ""
        if (url.startsWith("/") || url.startsWith("file://")) return url
        return replaceDimension(url, DIMENSION_THUMBNAIL)
    }

    /**
     * 获取单一无损原图直链（供大图或本地加载检测）
     */
    fun getRawUrlOnly(url: String, explicitRawUrl: String? = null): String? {
        if (!explicitRawUrl.isNullOrBlank()) return explicitRawUrl
        if (url.isBlank()) return null
        if (url.startsWith("/") || url.startsWith("file://")) {
            return findLocalRawCover(url)
        }
        return extractRawCdnUrl(url)
    }

    /**
     * 统一根据场景、网络状态以及本地环境，解析多清晰度降级候选列表
     */
    fun getCandidates(
        url: String,
        scenario: CoverScenario,
        isCellular: Boolean = false,
        explicitRawUrl: String? = null,
    ): List<String> {
        if (url.isBlank()) return emptyList()

        val isLocal = url.startsWith("/") || url.startsWith("file://")
        val list = mutableListOf<String>()

        if (isLocal) {
            val matchingRaw = explicitRawUrl.takeIf { !it.isNullOrBlank() } ?: findLocalRawCover(url)
            when (scenario) {
                CoverScenario.THUMBNAIL -> {
                    list.add(url)
                }
                CoverScenario.PLAYER, CoverScenario.FULLSCREEN_RAW -> {
                    if (!matchingRaw.isNullOrBlank()) {
                        list.add(matchingRaw)
                    }
                    if (!list.contains(url)) {
                        list.add(url)
                    }
                }
                CoverScenario.DETAIL -> {
                    list.add(url)
                }
            }
            return list
        }

        val hasResolutionToken = url.contains(REGEX_RESOLUTION)
        if (!hasResolutionToken) {
            return listOf(url)
        }

        val url1200 = replaceDimension(url, DIMENSION_PLAYER)
        val url800 = replaceDimension(url, DIMENSION_FALLBACK)
        val url500 = replaceDimension(url, DIMENSION_THUMBNAIL)
        val rawUrl = extractRawCdnUrl(url)

        when (scenario) {
            CoverScenario.THUMBNAIL -> {
                list.add(url500)
                list.add(url800)
            }
            CoverScenario.DETAIL -> {
                list.add(url1200)
                list.add(url800)
                list.add(url500)
            }
            CoverScenario.PLAYER -> {
                if (isCellular) {
                    list.add(url800)
                    list.add(url500)
                } else {
                    list.add(url1200)
                    list.add(url800)
                    list.add(url500)
                }
            }
            CoverScenario.FULLSCREEN_RAW -> {
                if (!rawUrl.isNullOrBlank()) {
                    list.add(rawUrl)
                }
                list.add(url1200)
                list.add(url800)
            }
        }

        return list.distinct()
    }

    /**
     * TV 端 QQ 音乐 CDN 超高清强升候选
     */
    fun upgradeTvCoverUrl(url: String, dimension: Int): String {
        if (url.isBlank()) return ""
        return url
            .replace(REGEX_T002, "T002R${dimension}x${dimension}M000")
            .replace(REGEX_T062, "T062R${dimension}x${dimension}M000")
    }

    /**
     * 本地缩略图匹配同目录下的无损原画大图 (cover_raw_ 或 webdav_raw_)
     */
    fun findLocalRawCover(url: String): String? {
        if (!url.startsWith("/") && !url.startsWith("file://")) return null
        return try {
            val path = if (url.startsWith("file://")) url.removePrefix("file://") else url
            val file = File(path)
            val parent = file.parentFile ?: return null
            val name = file.name
            val (baseHash, prefix) =
                when {
                    name.startsWith("cover_") && !name.startsWith("cover_raw_") -> {
                        name.removePrefix("cover_").substringBeforeLast(".") to "cover_raw_"
                    }
                    name.startsWith("webdav_") && !name.startsWith("webdav_raw_") -> {
                        name.removePrefix("webdav_").substringBeforeLast(".") to "webdav_raw_"
                    }
                    else -> return null
                }
            val extensions = listOf("jpg", "png", "webp", "jpeg")
            for (ext in extensions) {
                val candidate = File(parent, "$prefix$baseHash.$ext")
                if (candidate.exists() && candidate.length() > 512L) {
                    return "file://${candidate.absolutePath}"
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }
}
