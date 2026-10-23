package org.melodist.playback

import androidx.media3.exoplayer.ExoPlayer
import org.melodist.model.AudioQualityTier

/**
 * 播放器目标媒体源装配执行器。
 */
object PlaybackPlayerBinder {
    /**
     * 将解析完成的目标媒体源装配至 ExoPlayer 并启动播放。
     *
     * @param player 目标 ExoPlayer 播放器实例
     * @param target 解析完成的媒体目标结果
     * @param seekToMs 起播跳转毫秒数
     * @param isMuted 当前是否处于静音状态
     * @param targetVolume 目标音量
     * @param onPlaybackStarted 播放启动成功回调（参数：实际音质、是否命中缓存、文件缓存比例）
     * @param onWebDavPostAction WebDAV 歌曲后续自愈回调
     * @param onLocalFilePostAction 本地文件歌曲后续封面校验回调
     * @param onFailure 媒体加载失败回调
     */
    fun bindAndPlay(
        player: ExoPlayer,
        target: PlaybackTargetResult,
        seekToMs: Long,
        isMuted: Boolean,
        targetVolume: Float,
        onPlaybackStarted: (actualTier: AudioQualityTier, isCache: Boolean, fraction: Float) -> Unit,
        onWebDavPostAction: () -> Unit,
        onLocalFilePostAction: () -> Unit,
        onFailure: (message: String, allowRetry: Boolean) -> Unit,
    ) {
        when (target) {
            is PlaybackTargetResult.LocalFile -> {
                if (seekToMs > 0L) {
                    player.setMediaItem(target.mediaItem, seekToMs)
                } else {
                    player.setMediaItem(target.mediaItem)
                }
                player.prepare()
                player.play()
                onPlaybackStarted(target.actualTier, true, 1f)
                onLocalFilePostAction()
            }

            is PlaybackTargetResult.LocalStream -> {
                if (seekToMs > 0L) {
                    player.setMediaSource(target.mediaSource, seekToMs)
                } else {
                    player.setMediaSource(target.mediaSource)
                }
                player.prepare()
                player.play()
                onPlaybackStarted(target.actualTier, false, 0f)
            }

            is PlaybackTargetResult.WebDavLocalCache -> {
                if (seekToMs > 0L) {
                    player.setMediaItem(target.mediaItem, seekToMs)
                } else {
                    player.setMediaItem(target.mediaItem)
                }
                player.prepare()
                player.play()
                onPlaybackStarted(target.actualTier, true, 1f)
                onWebDavPostAction()
            }

            is PlaybackTargetResult.WebDavStream -> {
                if (seekToMs > 0L) {
                    player.setMediaSource(target.mediaSource, seekToMs)
                } else {
                    player.setMediaSource(target.mediaSource)
                }
                player.prepare()
                player.play()
                onPlaybackStarted(target.actualTier, false, 0f)
                onWebDavPostAction()
            }

            is PlaybackTargetResult.Online -> {
                if (seekToMs > 0L) {
                    player.setMediaSource(target.mediaSource, seekToMs)
                } else {
                    player.setMediaSource(target.mediaSource)
                }
                player.volume = if (isMuted) 0f else targetVolume
                player.prepare()
                player.play()
                onPlaybackStarted(target.actualTier, target.isDirectCached, target.fileCacheFraction)
            }

            is PlaybackTargetResult.Failure -> {
                onFailure(target.message, target.allowRetry)
            }
        }
    }
}
