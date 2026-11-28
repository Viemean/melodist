package org.melodist.mobile.ui.components

import androidx.compose.foundation.lazy.LazyListState
import org.melodist.model.RotatingQueueState
import java.util.concurrent.ConcurrentHashMap

/**
 * 移动端特色轮播卡片状态协调器。
 *
 * 单例持有各业务卡片的乱序队列与当前游标，以及卡片横向容器的滚动状态，
 * 跨导航出栈入栈生命周期保持稳定。
 */
object RotatingCardCoordinator {
    private val states = ConcurrentHashMap<String, RotatingQueueState<*>>()
    private val lazyListStates = ConcurrentHashMap<String, LazyListState>()

    /**
     * 获取或初始化指定业务标识的轮播队列状态。
     *
     * @param key 卡片业务唯一标识（如 "favorite_music", "favorite_albums"）
     * @return 对应的 [RotatingQueueState]
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> getOrCreate(key: String): RotatingQueueState<T> = states.computeIfAbsent(key) { RotatingQueueState<T>() } as RotatingQueueState<T>

    /**
     * 获取或初始化指定标识的卡片行横向滚动状态 [LazyListState]。
     *
     * @param key 横向行标识（如 "discover_hero_carousel"）
     * @return 对应的 [LazyListState]
     */
    fun getOrCreateLazyListState(key: String): LazyListState = lazyListStates.computeIfAbsent(key) { LazyListState() }

    /**
     * 移除指定标识的列表滚动状态，页面栈弹出销毁时及时释放。
     *
     * @param key 列表标识
     * @return 被移除的 [LazyListState]，若不存在返回 null
     */
    fun removeLazyListState(key: String): LazyListState? = lazyListStates.remove(key)

    /**
     * 宿主 Activity 销毁时清空所有缓存的 [LazyListState]，避免跨生命周期持有 Compose 布局引用。
     */
    fun clearLazyListStates() {
        lazyListStates.clear()
    }
}
