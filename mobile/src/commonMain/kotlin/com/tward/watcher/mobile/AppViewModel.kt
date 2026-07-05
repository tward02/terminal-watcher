package com.tward.watcher.mobile

import com.tward.watcher.core.client.WatcherClient
import com.tward.watcher.core.client.WatcherClientApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Factory indirection so tests can substitute a fake client. */
typealias ClientFactory = (host: String, port: Int, token: String, scope: CoroutineScope) -> WatcherClientApi

val defaultClientFactory: ClientFactory = { host, port, token, scope ->
    WatcherClient(host = host, port = port, token = token, scope = scope)
}

/**
 * State holder for the viewer app. Owns the [WatcherClientApi] for the current
 * connection and forwards fired hook notifications to the platform [Notifier].
 */
class AppViewModel(
    private val scope: CoroutineScope,
    private val notifier: Notifier,
    private val clientFactory: ClientFactory = defaultClientFactory,
) {
    private val _form = MutableStateFlow(ConnectForm())
    val form: StateFlow<ConnectForm> = _form

    private val _formError = MutableStateFlow<String?>(null)
    val formError: StateFlow<String?> = _formError

    private val _client = MutableStateFlow<WatcherClientApi?>(null)

    /** Non-null while a connection is active or being retried. */
    val client: StateFlow<WatcherClientApi?> = _client

    private var notificationJob: Job? = null

    fun updateForm(form: ConnectForm) {
        _form.value = form
        _formError.value = null
    }

    fun connect() {
        val validated = _form.value.validate().getOrElse { problem ->
            _formError.value = problem.message
            return
        }
        _formError.value = null

        val client = clientFactory(validated.host, validated.port, validated.token, scope)
        _client.value = client
        notificationJob = scope.launch {
            client.notifications.collect { notification ->
                notifier.notify(notification.title, notification.body)
            }
        }
        client.connect()
    }

    fun disconnect() {
        notificationJob?.cancel()
        notificationJob = null
        _client.value?.close()
        _client.value = null
    }
}
