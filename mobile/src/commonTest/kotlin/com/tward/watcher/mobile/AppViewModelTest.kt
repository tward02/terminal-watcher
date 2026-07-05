package com.tward.watcher.mobile

import com.tward.watcher.core.client.ConnectionState
import com.tward.watcher.core.client.WatcherClientApi
import com.tward.watcher.core.model.TerminalLine
import com.tward.watcher.core.protocol.HookNotification
import com.tward.watcher.core.protocol.ProcessStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class AppViewModelTest {

    private class FakeClient : WatcherClientApi {
        override val state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
        override val lines = MutableStateFlow<List<TerminalLine>>(emptyList())
        override val notifications = MutableSharedFlow<HookNotification>()
        override val processStatus = MutableStateFlow<ProcessStatus?>(null)

        var connectCalls = 0
        var closed = false

        override fun connect() {
            connectCalls++
        }

        override fun disconnect() {}

        override fun close() {
            closed = true
        }
    }

    private class RecordingNotifier : Notifier {
        val notified = mutableListOf<Pair<String, String>>()
        override fun notify(title: String, body: String) {
            notified += title to body
        }
    }

    private fun validForm() = ConnectForm(host = "10.0.0.5", port = "8765", token = "tok")

    @Test
    fun connectWithValidFormCreatesAndConnectsClient() = runTest {
        val fake = FakeClient()
        var factoryArgs: Triple<String, Int, String>? = null
        val viewModel = AppViewModel(
            scope = this,
            notifier = RecordingNotifier(),
            clientFactory = { host, port, token, _ ->
                factoryArgs = Triple(host, port, token)
                fake
            },
        )

        viewModel.updateForm(validForm())
        viewModel.connect()

        assertEquals(Triple("10.0.0.5", 8765, "tok"), factoryArgs)
        assertEquals(1, fake.connectCalls)
        assertNotNull(viewModel.client.value)
        assertNull(viewModel.formError.value)

        viewModel.disconnect()
    }

    @Test
    fun connectWithInvalidFormReportsErrorAndDoesNotConnect() = runTest {
        var factoryCalled = false
        val viewModel = AppViewModel(
            scope = this,
            notifier = RecordingNotifier(),
            clientFactory = { _, _, _, _ ->
                factoryCalled = true
                FakeClient()
            },
        )

        viewModel.updateForm(ConnectForm(host = "", port = "8765", token = "t"))
        viewModel.connect()

        assertEquals(false, factoryCalled)
        assertNull(viewModel.client.value)
        assertTrue("Host" in viewModel.formError.value!!)
    }

    @Test
    fun editingTheFormClearsTheError() = runTest {
        val viewModel = AppViewModel(scope = this, notifier = RecordingNotifier())
        viewModel.updateForm(ConnectForm(host = "", port = "8765", token = "t"))
        viewModel.connect()
        assertNotNull(viewModel.formError.value)

        viewModel.updateForm(validForm())
        assertNull(viewModel.formError.value)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun hookNotificationsAreForwardedToTheNotifier() = runTest {
        val fake = FakeClient()
        val notifier = RecordingNotifier()
        val viewModel = AppViewModel(
            scope = this,
            notifier = notifier,
            clientFactory = { _, _, _, _ -> fake },
        )

        viewModel.updateForm(validForm())
        viewModel.connect()
        // Let the notification collector launched by connect() subscribe first;
        // an unbuffered SharedFlow drops emissions with no subscribers.
        advanceUntilIdle()
        fake.notifications.emit(HookNotification("h", "Build failed", "exit 1", 1))
        advanceUntilIdle()

        assertEquals(listOf("Build failed" to "exit 1"), notifier.notified)
        viewModel.disconnect()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun disconnectClosesClientAndStopsForwarding() = runTest {
        val fake = FakeClient()
        val notifier = RecordingNotifier()
        val viewModel = AppViewModel(
            scope = this,
            notifier = notifier,
            clientFactory = { _, _, _, _ -> fake },
        )

        viewModel.updateForm(validForm())
        viewModel.connect()
        advanceUntilIdle()
        viewModel.disconnect()

        assertTrue(fake.closed)
        assertNull(viewModel.client.value)

        // Emission after disconnect must not reach the notifier (no active collector).
        assertEquals(false, fake.notifications.tryEmit(HookNotification("h", "late", "x", 2)))
        assertTrue(notifier.notified.isEmpty())
    }
}
