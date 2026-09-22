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
        // 避免新一轮的首个元素与上一轮的最后一个元素相同
        if (lastItem != null && shuffled.firstOrNull() == lastItem && shuffled.size > 1) {
            val first = shuffled.removeAt(0)
            shuffled.add(first)
        }
        return shuffled
    }
}
