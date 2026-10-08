package app.podkhod.platform

internal actual fun platformLog(level: LogLevel, tag: String, message: String, error: Throwable?) {
    when (level) {
        LogLevel.WARN -> android.util.Log.w(tag, message, error)
        LogLevel.ERROR -> android.util.Log.e(tag, message, error)
    }
}
