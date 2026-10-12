package org.melodist.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.melodist.api.MusicApiService
import org.melodist.api.getUserMusicPreference
import org.melodist.api.setUserMusicPreference
import org.melodist.model.MusicPreferenceItem
import org.melodist.model.UserMusicPreference

/**
 * 用户听歌偏好与风格设置数据管理器。
 */
object UserMusicPreferenceManager {
    private val _preferenceFlow = MutableStateFlow<UserMusicPreference?>(null)
    val preferenceFlow: StateFlow<UserMusicPreference?> = _preferenceFlow.asStateFlow()

    private val _isLoadingFlow = MutableStateFlow(false)
    val isLoadingFlow: StateFlow<Boolean> = _isLoadingFlow.asStateFlow()

    private val _isSavingFlow = MutableStateFlow(false)
    val isSavingFlow: StateFlow<Boolean> = _isSavingFlow.asStateFlow()

    private val apiService = MusicApiService()

    /**
     * 刷新并获取最新听歌偏好。
     *
     * @param force 是否强制重新从网络拉取
     */
    suspend fun refresh(force: Boolean = false): UserMusicPreference? =
        withContext(Dispatchers.IO) {
            if (!force && _preferenceFlow.value != null) {
                return@withContext _preferenceFlow.value
            }
            _isLoadingFlow.value = true
            try {
                val remote = apiService.getUserMusicPreference()
                if (remote != null) {
                    _preferenceFlow.value = remote
                }
                remote
            } finally {
                _isLoadingFlow.value = false
            }
        }

    /**
     * 保存偏好设置并同步本地状态。
     *
     * @param items 各项偏好设置
     * @param activeKey 本次变动的 ProfileKey
     * @return 是否保存成功
     */
    suspend fun savePreferences(
        items: List<MusicPreferenceItem>,
        activeKey: String = "",
    ): Boolean =
        withContext(Dispatchers.IO) {
            _isSavingFlow.value = true
            try {
                val success = apiService.setUserMusicPreference(items, activeKey)
                if (success) {
                    refresh(force = true)
                }
                success
            } finally {
                _isSavingFlow.value = false
            }
        }

    /**
     * 重置偏好至默认状态（所有项分值设为 50）。
     *
     * @return 是否重置成功
     */
    suspend fun resetPreferences(): Boolean =
        withContext(Dispatchers.IO) {
            val current = _preferenceFlow.value ?: return@withContext false
            val resetItems =
                current.scores.map {
                    it.copy(currentScore = 50)
                }
            savePreferences(resetItems)
        }
}
