package app.podkhod.platform

import platform.Foundation.NSLog

internal actual fun platformLog(level: LogLevel, tag: String, message: String, error: Throwable?) {
    NSLog("%@", "[$level] $tag: $message${error?.let { "\n" + it.stackTraceToString() }.orEmpty()}")
}
