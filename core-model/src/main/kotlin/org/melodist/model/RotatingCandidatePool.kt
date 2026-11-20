package org.melodist.model

/**
 * 通用轮播候选池与洗牌无序抽取工具类。
 */
object RotatingCandidatePool {
    /**
     * 根据“前 fixedCount 个固定最新 + 剩余随机抽取 randomCount 个”规则构建候选池。
     * 若总数小于等于 targetTotalCount（默认 30），则直接返回原始列表。
     */
    fun <T> buildCandidatePool(
        source: List<T>,
        fixedCount: Int = 5,
        randomCount: Int = 25,
    ): List<T> {
        val targetTotal = fixedCount + randomCount
        if (source.size <= targetTotal) {
            return source
        }
        val fixed = source.take(fixedCount)
        val remaining = source.drop(fixedCount).shuffled().take(randomCount)
        return fixed + remaining
    }

    /**
     * 对候选池进行洗牌生成无序序列，保证相邻两轮切换时不会连续出现相同元素。
     */
    fun <T> createShuffledQueue(
        pool: List<T>,
        lastItem: T? = null,
    ): List<T> {
        if (pool.size <= 1) return pool
        val shuffled = pool.shuffled().toMutableList()
        // 首尾去重换位
        if (lastItem != null && shuffled.firstOrNull() == lastItem && shuffled.size > 1) {
            val first = shuffled.removeAt(0)
            shuffled.add(first)
        }
        return shuffled
    }
}

/**
 * 维持轮播卡片跨页面生命周期状态的通用队列持有者。
 *
 * 保证在源列表未发生实质变更时，不重置播放索引与乱序序列，
 * 避免因页面出栈/入栈重新组合引发的意外切歌跳动。
 */
class RotatingQueueState<T> {
    private var sourceList: List<T> = emptyList()
    var candidatePool: List<T> = emptyList()
        private set
    var shuffledQueue: List<T> = emptyList()
        private set
    var currentIndex: Int = 0
        private set

    /**
     * 更新源列表。若源列表内容与既有列表一致且队列已建立，则跳过重洗，保持原位。
     *
     * @param newSource 最新数据源列表
     * @param fixedCount 优先保留的前置固定条数（0 表示不抽样，使用全量源列表）
     * @param randomCount 随机抽取的补充条数
     * @return 若发生了重新洗牌返回 true；若复用既有队列返回 false
     */
    fun updateSource(
        newSource: List<T>,
        fixedCount: Int = 0,
        randomCount: Int = 0,
    ): Boolean {
        val snapshot = newSource.toList()
        if (sourceList == snapshot && shuffledQueue.isNotEmpty()) {
            return false
        }
        val lastItem = currentItem
        sourceList = snapshot
        candidatePool =
            if (fixedCount > 0 || randomCount > 0) {
                RotatingCandidatePool.buildCandidatePool(snapshot, fixedCount, randomCount)
            } else {
                snapshot
            }

        if (candidatePool.isNotEmpty()) {
            shuffledQueue = RotatingCandidatePool.createShuffledQueue(candidatePool, lastItem = lastItem)
            currentIndex = 0
        } else {
            shuffledQueue = emptyList()
            currentIndex = 0
        }
        return true
    }

    /**
     * 前进至下一个候选元素。若遍历至末尾，则在保留尾项去重的前提下重新洗牌。
     *
     * @return 切换后的最新索引
     */
    fun advance(): Int {
        if (shuffledQueue.isEmpty()) return 0
        val next = currentIndex + 1
        if (next < shuffledQueue.size) {
            currentIndex = next
        } else {
            val lastItem = shuffledQueue.lastOrNull()
            shuffledQueue = RotatingCandidatePool.createShuffledQueue(candidatePool, lastItem = lastItem)
            currentIndex = 0
        }
        return currentIndex
    }

    /**
     * 获取当前应展示的元素。
     */
    val currentItem: T?
        get() = shuffledQueue.getOrNull(currentIndex) ?: candidatePool.firstOrNull() ?: sourceList.firstOrNull()
}
