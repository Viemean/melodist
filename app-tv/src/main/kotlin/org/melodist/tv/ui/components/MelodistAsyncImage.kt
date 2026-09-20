package org.melodist.tv.ui.components

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import org.melodist.api.MusicApiService

private val T002_CDN_REGEX = Regex("T002R\\d+x\\d+M000")
private val T062_CDN_REGEX = Regex("T062R\\d+x\\d+M000")

/**
 * 支持多分辨率降级与边缘安全裁切的封面组件
 */
@Composable
fun MelodistAsyncImage(
    coverUrl: String,
    albumMid: String = "",
    artistMid: String = "",
    visualMid: String = "",
    songMid: String = "",
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    shape: Shape? = null,
    bleedCrop: Boolean = true,
    contentScale: ContentScale = ContentScale.Crop,
    preferRawCover: Boolean = false,
) {
    var dynamicVisualMid by remember(songMid) { mutableStateOf("") }

    LaunchedEffect(songMid, coverUrl, albumMid, visualMid) {
        if (coverUrl.isBlank() &&
            albumMid.isBlank() &&
            visualMid.isBlank() &&
            dynamicVisualMid.isBlank() &&
            songMid.isNotBlank() &&
            !songMid.startsWith("webdav_")
        ) {
            val vsMid = MusicApiService().getSongVisualMid(songMid)
            if (!vsMid.isNullOrBlank()) {
                dynamicVisualMid = vsMid
            }
        }
    }

    val effectiveVisualMid = visualMid.ifBlank { dynamicVisualMid }

    val candidates =
        remember(coverUrl, albumMid, artistMid, effectiveVisualMid, preferRawCover) {
            val list = mutableListOf<String>()
            val isLocalFile = coverUrl.startsWith("/") || coverUrl.startsWith("file://")
            if (isLocalFile && coverUrl.isNotBlank()) {
                val normalized = if (coverUrl.startsWith("/")) "file://$coverUrl" else coverUrl
                if (preferRawCover) {
                    val rawCandidate = org.melodist.data.RawCoverHelper.findMatchingRawCoverUrl(normalized)
                    if (!rawCandidate.isNullOrBlank() && rawCandidate != normalized) {
                        list.add(rawCandidate)
                    }
                }
                list.add(normalized)
            } else {
                // QQ 音乐 CDN 超高清 1200x1200 与 800x800 优先强升
                if (coverUrl.isNotBlank()) {
                    val upgraded1200 =
                        coverUrl
                            .replace(T002_CDN_REGEX, "T002R1200x1200M000")
                            .replace(T062_CDN_REGEX, "T062R1200x1200M000")
                    val upgraded800 =
                        coverUrl
                            .replace(T002_CDN_REGEX, "T002R800x800M000")
                            .replace(T062_CDN_REGEX, "T062R800x800M000")
                    if (upgraded1200 != coverUrl) {
                        list.add(upgraded1200)
                    }
                    if (upgraded800 != coverUrl && !list.contains(upgraded800)) {
                        list.add(upgraded800)
                    }
                }
                if (albumMid.isNotBlank()) {
                    list.addAll(MusicApiService.getAlbumCoverCandidates(albumMid).filter { !list.contains(it) })
                }
                if (coverUrl.isNotBlank() && !list.contains(coverUrl)) {
                    list.add(coverUrl)
                }
                if (effectiveVisualMid.isNotBlank()) {
                    list.addAll(MusicApiService.getSingleCoverCandidates(effectiveVisualMid).filter { !list.contains(it) })
                }
                if (artistMid.isNotBlank()) {
                    list.addAll(MusicApiService.getSingerAvatarCandidates(artistMid).filter { !list.contains(it) })
                }
            }
            list
        }

    var candidateIndex by remember(candidates) { mutableIntStateOf(0) }
    val currentUrl = candidates.getOrNull(candidateIndex).orEmpty()

    if (currentUrl.isNotEmpty()) {
        val finalModifier =
            remember(modifier, shape, bleedCrop) {
                var m = modifier
                if (shape != null) {
                    m = m.clip(shape)
                }
                if (bleedCrop) {
                    m =
                        m.graphicsLayer {
                            scaleX = 1.05f
                            scaleY = 1.05f
                        }
                }
                m
            }

        AsyncImage(
            model = currentUrl,
            contentDescription = contentDescription,
            modifier = finalModifier,
            contentScale = contentScale,
            onError = {
                if (candidateIndex + 1 < candidates.size) {
                    candidateIndex++
                }
            },
        )
    }
}
