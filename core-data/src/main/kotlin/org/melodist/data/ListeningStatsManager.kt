package org.melodist.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.melodist.api.MusicApiService
import org.melodist.api.UserSession
import org.melodist.api.getListeningRecordReport
import org.melodist.model.ListeningReportData
import org.melodist.model.ListeningReportPeriod
import java.util.concurrent.ConcurrentHashMap

/**
 * 用户听歌数据报告与足迹统计缓存管理器。
 */
object ListeningStatsManager {
    private val apiService = MusicApiService()

    private val _currentStats = MutableStateFlow<ListeningReportData?>(null)
    val currentStats: StateFlow<ListeningReportData?> = _currentStats.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _selectedPeriod = MutableStateFlow(ListeningReportPeriod.Day)
    val selectedPeriod: StateFlow<ListeningReportPeriod> = _selectedPeriod.asStateFlow()

    private val _selectedDate = MutableStateFlow<String?>(null)
    val selectedDate: StateFlow<String?> = _selectedDate.asStateFlow()

    private val cache = ConcurrentHashMap<String, ListeningReportData>()

    /**
     * 切换统计周期（日/周/月/年）。
     */
    suspend fun setPeriod(period: ListeningReportPeriod) {
        if (_selectedPeriod.value == period) return
        _selectedPeriod.value = period
        _selectedDate.value = null
        loadStats(period, null)
    }

    /**
     * 切换选定日期。
     */
    suspend fun setDate(date: String?) {
        _selectedDate.value = date
        loadStats(_selectedPeriod.value, date)
    }

    /**
     * 刷新当前维度的统计数据。
     */
    suspend fun refresh(force: Boolean = true) {
        loadStats(_selectedPeriod.value, _selectedDate.value, force = force)
    }

    private suspend fun loadStats(
        period: ListeningReportPeriod,
        date: String?,
        force: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        if (!UserSession.isLoggedIn) {
            _currentStats.value = null
            return@withContext
        }

        val cacheKey = "${period.typeCode}_${date ?: "latest"}"
        if (!force) {
            val cached = cache[cacheKey]
            if (cached != null) {
                _currentStats.value = cached
                return@withContext
            }
        }

        _isLoading.value = true
        try {
            val report = apiService.getListeningRecordReport(period = period, date = date)
            cache[cacheKey] = report
            _currentStats.value = report
            if (date == null && report.dateKey.isNotBlank()) {
                _selectedDate.value = report.dateKey
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            android.util.Log.w("ListeningStatsManager", "Failed to load listening stats: period=$period, date=$date", e)
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * 清理内存缓存。
     */
    fun clearCache() {
        cache.clear()
        _currentStats.value = null
    }
}
