package org.melodist.data

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

object AppLifecycleManager : Application.ActivityLifecycleCallbacks, ComponentCallbacks2 {
    private var startedActivityCount = 0
    private var isInitialized = false

    private val _isForeground = MutableStateFlow(false)
    val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    var onTrimMemoryAction: ((level: Int) -> Unit)? = null

    fun init(application: Application) {
        if (isInitialized) return
        isInitialized = true
        application.registerActivityLifecycleCallbacks(this)
        application.registerComponentCallbacks(this)
    }

    suspend fun awaitForeground() {
        if (_isForeground.value) return
        _isForeground.first { it }
    }

    override fun onActivityStarted(activity: Activity) {
        startedActivityCount++
        if (startedActivityCount == 1) {
            _isForeground.value = true
        }
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivityCount--
        if (startedActivityCount <= 0) {
            startedActivityCount = 0
            _isForeground.value = false
            onTrimMemoryAction?.invoke(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        }
    }

    override fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            onTrimMemoryAction?.invoke(level)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {}

    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Java")
    override fun onLowMemory() {
        onTrimMemoryAction?.invoke(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
    }

    override fun onActivityCreated(
        activity: Activity,
        savedInstanceState: Bundle?,
    ) {}

    override fun onActivityResumed(activity: Activity) {}

    override fun onActivityPaused(activity: Activity) {}

    override fun onActivitySaveInstanceState(
        activity: Activity,
        outState: Bundle,
    ) {}

    override fun onActivityDestroyed(activity: Activity) {}
}
