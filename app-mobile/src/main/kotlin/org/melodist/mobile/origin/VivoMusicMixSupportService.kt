package org.melodist.mobile.origin

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.RatingCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.media.MediaBrowserServiceCompat
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.melodist.data.AppSettingsManager
import org.melodist.data.LocalMusicManager
import org.melodist.data.UserLibraryCacheManager
import org.melodist.model.LyricLine
import org.melodist.model.Song
import org.melodist.playback.PlaybackLoopMode
import org.melodist.playback.PlaybackManager
import java.util.Locale

/**
 * 针对 vivo OriginOS 原子随身听与系统控制中心的开放接入服务。
 *
 * 响应 "com.vivo.musicwidgetmix.support.service" Intent Action，
 * 按照 OriginOS CooperateController (c0.java) 规范对齐实现：
 * 1. 基础播控（play/pause/skipToNext/skipToPrevious/seekTo/playFromMediaId）；
 * 2. 循环模式切换（vivomusicmix.media.action.PLAY_MODE 与 vivomusicmix.media.metadata.LOOP_MODE）；
 * 3. 逐行双语滚动歌词（vivomusicmix.extra.lrc_change、vivomusicmix.extra.key.lyric）；
 * 4. 红心双向收藏联动（RatingCompat.newHeartRating / onSetRating）；
 * 5. 歌单分页与增量同步（vivomusicmix_current_list/favorite_list/local_list，包含 has_more 与 media_page 标记）；
 * 6. 专辑封面位图解码（写入 ALBUM_ART / DISPLAY_ICON）。
 *
 * 已对齐 OriginOS 开放标准，但由于系统端原子随身听暂未正式开放第三方应用白名单接入，待系统侧后续开放后即可直接生效。
 */
class VivoMusicMixSupportService : MediaBrowserServiceCompat() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var mediaSessionCompat: MediaSessionCompat? = null
    private var lastCoverSongMid: String? = null
    private var cachedCoverBitmap: Bitmap? = null

    companion object {
        private const val TAG = "VivoMusicMixSupport"
        private const val ROOT_ID = "vivomusicmix_root"

        // OriginOS 随身听预置的三类歌单标识
        private const val LIST_CURRENT = "vivomusicmix_current_list"
        private const val LIST_FAVORITE = "vivomusicmix_favorite_list"
        private const val LIST_LOCAL = "vivomusicmix_local_list"

        // vivo 专有 MetaData 与 Action 键值
        private const val ACTION_LRC_CHANGE = "vivomusicmix.extra.lrc_change"
        private const val KEY_ACTION = "vivomusicmix.meida.extra.key.action"
        private const val KEY_MEDIA_ID = "vivomusicmix.extra.key.meidia_id"
        private const val KEY_LYRIC = "vivomusicmix.extra.key.lyric"
        private const val KEY_LOOP_MODE = "vivomusicmix.media.metadata.LOOP_MODE"
        private const val KEY_SUPPORT_EVENT = "vivomusicmix.media.metadata.support_event"
        private const val KEY_CAST_STATE = "vivomusicmix.media.metadata.cast_state"
        private const val ACTION_PLAY_MODE = "vivomusicmix.media.action.PLAY_MODE"

        // vivo 歌单分页通信 Extras
        private const val KEY_REQUEST_PAGE = "vivomusicmix_key_media_page"
        private const val KEY_HAS_MORE = "vivomusicmix_key_has_more"
        private const val PAGE_SIZE = 50

        // 声明支持所有播控与歌词事件（位掩码: 1|2|4 = 7）
        private const val SUPPORT_EVENT_VALUE = 7L
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "VivoMusicMixSupportService onCreate")

        val session = MediaSessionCompat(this, "VivoMusicMixSession").apply {
            setCallback(sessionCallback)
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS,
            )
            isActive = true
        }
        mediaSessionCompat = session
        sessionToken = session.sessionToken

        observePlaybackState()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "VivoMusicMixSupportService onDestroy")
        serviceScope.cancel()
        mediaSessionCompat?.release()
        mediaSessionCompat = null
        cachedCoverBitmap = null
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?,
    ): BrowserRoot {
        Log.i(TAG, "onGetRoot called by $clientPackageName (uid: $clientUid)")
        return BrowserRoot(ROOT_ID, null)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>,
    ) {
        onLoadChildrenInternal(parentId, result, 0)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>,
        options: Bundle,
    ) {
        val pageIndex = options.getInt(KEY_REQUEST_PAGE, 0)
        onLoadChildrenInternal(parentId, result, pageIndex)
    }

    private fun onLoadChildrenInternal(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>,
        pageIndex: Int,
    ) {
        Log.i(TAG, "onLoadChildrenInternal parentId=$parentId, page=$pageIndex")
        val allSongs = when (parentId) {
            LIST_CURRENT -> PlaybackManager.playlist.value
            LIST_FAVORITE -> UserLibraryCacheManager.favoriteSongsFlow.value
            LIST_LOCAL -> LocalMusicManager.scannedSongsFlow.value
            else -> emptyList()
        }

        val fromIndex = pageIndex * PAGE_SIZE
        if (fromIndex >= allSongs.size && pageIndex > 0) {
            result.sendResult(mutableListOf())
            return
        }

        val toIndex = minOf(fromIndex + PAGE_SIZE, allSongs.size)
        val pageSongs = if (allSongs.isNotEmpty()) allSongs.subList(fromIndex, toIndex) else emptyList()
        val hasMore = toIndex < allSongs.size

        val mediaItems = pageSongs.mapIndexed { index, song ->
            val isLastItem = index == pageSongs.lastIndex
            toMediaItem(
                song = song,
                isLast = isLastItem,
                hasMore = hasMore,
                nextPage = pageIndex + 1,
            )
        }.toMutableList()

        result.sendResult(mediaItems)
    }

    private fun toMediaItem(
        song: Song,
        isLast: Boolean,
        hasMore: Boolean,
        nextPage: Int,
    ): MediaBrowserCompat.MediaItem {
        val extras = Bundle()
        if (isLast) {
            extras.putBoolean(KEY_HAS_MORE, hasMore)
            extras.putInt(KEY_REQUEST_PAGE, nextPage)
        }

        val desc = MediaDescriptionCompat.Builder()
            .setMediaId(song.songMid)
            .setTitle(song.name)
            .setSubtitle(song.singer)
            .setDescription(song.album)
            .setIconUri(if (song.coverUrl.isNotBlank()) Uri.parse(song.coverUrl) else null)
            .setExtras(extras)
            .build()
        return MediaBrowserCompat.MediaItem(desc, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
    }

    private val sessionCallback = object : MediaSessionCompat.Callback() {
        override fun onPlay() {
            PlaybackManager.play()
        }

        override fun onPause() {
            PlaybackManager.pause()
        }

        override fun onSkipToNext() {
            PlaybackManager.playNext()
        }

        override fun onSkipToPrevious() {
            PlaybackManager.playPrevious()
        }

        override fun onSeekTo(pos: Long) {
            PlaybackManager.seekTo(pos)
        }

        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
            if (mediaId.isNullOrBlank()) return
            val playlist = PlaybackManager.playlist.value
            val targetSong = playlist.find { it.songMid == mediaId || it.songId.toString() == mediaId }
                ?: UserLibraryCacheManager.favoriteSongsFlow.value.find { it.songMid == mediaId || it.songId.toString() == mediaId }
                ?: LocalMusicManager.scannedSongsFlow.value.find { it.songMid == mediaId || it.songId.toString() == mediaId }
            if (targetSong != null) {
                PlaybackManager.playSong(targetSong)
            }
        }

        override fun onSetRating(rating: RatingCompat?) {
            PlaybackManager.toggleCurrentSongFavorite()
        }

        override fun onCustomAction(action: String?, extras: Bundle?) {
            if (action == ACTION_PLAY_MODE) {
                val loopModeInt = extras?.getInt(KEY_LOOP_MODE, 1) ?: 1
                val targetMode = when (loopModeInt) {
                    2 -> PlaybackLoopMode.SingleRepeat
                    3 -> PlaybackLoopMode.Shuffle
                    else -> PlaybackLoopMode.ListRepeat
                }
                PlaybackManager.setLoopMode(targetMode)
            }
        }
    }

    private fun observePlaybackState() {
        serviceScope.launch {
            PlaybackManager.isPlaying.collectLatest { syncPlaybackState() }
        }
        serviceScope.launch {
            PlaybackManager.currentPositionMs.collectLatest { syncPlaybackState() }
        }
        serviceScope.launch {
            PlaybackManager.currentSong.collectLatest { song ->
                loadCoverBitmapAndSyncMetadata(song)
                syncLyrics(song, PlaybackManager.lyrics.value)
            }
        }
        serviceScope.launch {
            PlaybackManager.loopMode.collectLatest {
                syncMetadata(PlaybackManager.currentSong.value, cachedCoverBitmap)
            }
        }
        serviceScope.launch {
            PlaybackManager.favoriteSongMids.collectLatest {
                syncMetadata(PlaybackManager.currentSong.value, cachedCoverBitmap)
                notifyChildrenChanged(LIST_FAVORITE)
            }
        }
        serviceScope.launch {
            PlaybackManager.playlist.collectLatest {
                notifyChildrenChanged(LIST_CURRENT)
            }
        }
        serviceScope.launch {
            LocalMusicManager.scannedSongsFlow.collectLatest {
                notifyChildrenChanged(LIST_LOCAL)
            }
        }
        serviceScope.launch {
            PlaybackManager.lyrics.collectLatest { lyrics ->
                syncLyrics(PlaybackManager.currentSong.value, lyrics)
            }
        }
    }

    private fun syncPlaybackState() {
        val session = mediaSessionCompat ?: return
        val isPlaying = PlaybackManager.isPlaying.value
        val state = if (isPlaying) {
            PlaybackStateCompat.STATE_PLAYING
        } else {
            PlaybackStateCompat.STATE_PAUSED
        }
        val pos = PlaybackManager.currentPositionMs.value.coerceAtLeast(0L)
        val actions = PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
            PlaybackStateCompat.ACTION_SEEK_TO or
            PlaybackStateCompat.ACTION_SET_RATING or
            PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID

        val stateCompat = PlaybackStateCompat.Builder()
            .setActions(actions)
            .setState(state, pos, 1f)
            .build()
        session.setPlaybackState(stateCompat)
    }

    private fun loadCoverBitmapAndSyncMetadata(song: Song?) {
        if (song == null) {
            cachedCoverBitmap = null
            lastCoverSongMid = null
            syncMetadata(null, null)
            return
        }

        if (song.songMid == lastCoverSongMid && cachedCoverBitmap != null) {
            syncMetadata(song, cachedCoverBitmap)
            return
        }

        // 立即响应文字元数据更新
        syncMetadata(song, null)

        val coverUrl = song.coverUrl
        if (coverUrl.isBlank()) {
            cachedCoverBitmap = null
            lastCoverSongMid = song.songMid
            return
        }

        serviceScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    val request = ImageRequest.Builder(this@VivoMusicMixSupportService)
                        .data(coverUrl)
                        .build()
                    val result = SingletonImageLoader.get(this@VivoMusicMixSupportService).execute(request)
                    result.image?.toBitmap()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to load cover bitmap for ${song.name}", e)
                    null
                }
            }
            if (PlaybackManager.currentSong.value?.songMid == song.songMid) {
                cachedCoverBitmap = bitmap
                lastCoverSongMid = song.songMid
                syncMetadata(song, bitmap)
            }
        }
    }

    private fun syncMetadata(song: Song?, coverBitmap: Bitmap?) {
        val session = mediaSessionCompat ?: return
        if (song == null) {
            session.setMetadata(null)
            return
        }
        val isFav = PlaybackManager.favoriteSongMids.value.contains(song.songMid)
        val loopModeInt = when (PlaybackManager.loopMode.value) {
            PlaybackLoopMode.ListRepeat -> 1L
            PlaybackLoopMode.SingleRepeat -> 2L
            PlaybackLoopMode.Shuffle -> 3L
        }

        val metadataBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, song.songMid)
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, song.name)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, song.singer)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, song.album)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, song.durationSeconds * 1000L)
            .putRating(MediaMetadataCompat.METADATA_KEY_RATING, RatingCompat.newHeartRating(isFav))
            .putLong(KEY_LOOP_MODE, loopModeInt)
            .putLong(KEY_SUPPORT_EVENT, SUPPORT_EVENT_VALUE)
            .putLong(KEY_CAST_STATE, 0L)

        if (coverBitmap != null && !coverBitmap.isRecycled) {
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, coverBitmap)
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, coverBitmap)
        }
        if (song.coverUrl.isNotBlank()) {
            metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, song.coverUrl)
            metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, song.coverUrl)
        }

        session.setMetadata(metadataBuilder.build())
    }

    private fun syncLyrics(song: Song?, lyrics: List<LyricLine>) {
        val session = mediaSessionCompat ?: return
        if (song == null) return
        val lrcText = formatLyricsToLrc(lyrics)
        val extras = Bundle().apply {
            putString(KEY_ACTION, ACTION_LRC_CHANGE)
            putString(KEY_MEDIA_ID, song.songMid)
            putString(KEY_LYRIC, lrcText)
        }
        session.setExtras(extras)
    }

    private fun formatLyricsToLrc(lyrics: List<LyricLine>): String {
        if (lyrics.isEmpty()) return ""
        val showBilingual = AppSettingsManager.settings.value.showBilingualLyrics
        val sb = StringBuilder()
        for (line in lyrics) {
            val ms = line.timestampMs
            val min = ms / 60000
            val sec = (ms % 60000) / 1000
            val hundredths = (ms % 1000) / 10
            val text = if (showBilingual && line.hasTranslation) {
                "${line.text}^${line.transText}"
            } else {
                line.text
            }
            sb.append(String.format(Locale.US, "[%02d:%02d.%02d]%s\n", min, sec, hundredths, text))
        }
        return sb.toString()
    }
}
