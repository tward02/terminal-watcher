package com.tward.watcher.mobile

/**
 * Raises a local notification on the current platform. Implementations are
 * constructed at each platform's entry point and handed to [AppViewModel]:
 * Android uses NotificationManager, iOS uses UNUserNotificationCenter and the
 * JVM preview prints to the console.
 */
interface Notifier {
    fun notify(title: String, body: String)
}

/** Fallback used by tests and the desktop preview window. */
class ConsoleNotifier : Notifier {
    override fun notify(title: String, body: String) {
        println("[notification] $title: $body")
    }
}
