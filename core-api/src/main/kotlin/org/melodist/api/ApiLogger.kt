package org.melodist.api

/**
 * 跨平台轻量级日志抽象门面
 * 允许 Android 宿主端注入 Log.w/e，在纯 JVM 测试与非 Android 环境下默认输出至标准错误流。
 */
object ApiLogger {
    var logger: ((priority: Int, tag: String, message: String, throwable: Throwable?) -> Unit)? = null

    fun d(
        tag: String,
        message: String,
        throwable: Throwable? = null,
    ) {
        val custom = logger
        if (custom != null) {
            custom(3, tag, message, throwable)
        } else {
            println("DEBUG: [$tag] $message" + (throwable?.let { " - ${it.message}" } ?: ""))
        }
    }

    fun i(
        tag: String,
        message: String,
        throwable: Throwable? = null,
    ) {
        val custom = logger
        if (custom != null) {
            custom(4, tag, message, throwable)
        } else {
            println("INFO: [$tag] $message" + (throwable?.let { " - ${it.message}" } ?: ""))
        }
    }

    fun w(
        tag: String,
        message: String,
        throwable: Throwable? = null,
    ) {
        val custom = logger
        if (custom != null) {
            custom(5, tag, message, throwable)
        } else {
            System.err.println("WARN: [$tag] $message" + (throwable?.let { " - ${it.message}" } ?: ""))
        }
    }

    fun e(
        tag: String,
        message: String,
        throwable: Throwable? = null,
    ) {
        val custom = logger
        if (custom != null) {
            custom(6, tag, message, throwable)
        } else {
            System.err.println("ERROR: [$tag] $message" + (throwable?.let { " - ${it.message}" } ?: ""))
        }
    }
}
