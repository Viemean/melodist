package org.melodist.mobile.usb

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/**
 * 管理 USB 外设热插拔系统意图接收组件的动态启用与禁用。
 */
object UsbAttachComponentManager {
    private const val TAG = "UsbAttachManager"
    private const val ALIAS_CLASS_NAME = "org.melodist.mobile.UsbAudioAttachReceiverActivity"

    /**
     * 根据 USB 独占开关状态动态启用或禁用系统级 USB_DEVICE_ATTACHED 意图接收组件。
     *
     * @param context 上下文对象
     * @param enabled 是否启用系统插拔意图接收
     */
    fun setEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        val appContext = context.applicationContext
        val componentName = ComponentName(appContext, ALIAS_CLASS_NAME)
        val targetState =
            if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }

        try {
            val currentState = appContext.packageManager.getComponentEnabledSetting(componentName)
            if (currentState != targetState) {
                appContext.packageManager.setComponentEnabledSetting(
                    componentName,
                    targetState,
                    PackageManager.DONT_KILL_APP,
                )
                Log.i(TAG, "USB attach receiver component state updated: enabled=$enabled")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update USB attach component enabled setting", e)
        }
    }
}
