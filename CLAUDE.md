# CLAUDE.md

Guidance for Claude Code when working in this repository.

## What this project is

Terminal Watcher streams the output of a terminal command from a desktop machine to a phone. Three Gradle modules:

- `core` - Kotlin Multiplatform library shared by both sides: wire protocol (`protocol/Protocol.kt`), hook model and engine (`hooks/`), line ring buffer, and the reconnecting WebSocket client (`client/WatcherClient.kt`) used by the mobile app.
- `desktop` - Kotlin/JVM CLI app. Entry point `desktop/src/main/kotlin/com/tward/watcher/desktop/Main.kt`. Wraps a child process (`run` mode) or reads stdin (`pipe` mode), serves a Ktor CIO WebSocket server with token auth (`server/WatcherServer.kt`), and executes hook actions (`actions/DesktopActionExecutor.kt`).
- `mobile` - Compose Multiplatform viewer app (Android + iOS + a JVM "preview" target). Shared UI in `commonMain`, platform notifiers and entry points in `androidMain` / `iosMain` / `previewMain`.

## Build environment constraints (important)

- This machine (Windows) has NO Android SDK. The Android target and plugin of `:mobile` are applied conditionally in `mobile/build.gradle.kts` only when a SDK is detected (`local.properties` sdk.dir, `ANDROID_HOME`, or `ANDROID_SDK_ROOT`). Never assume `:mobile:assembleDebug` can run here.
- iOS targets (in `core` and `mobile`) are enabled only on macOS hosts, guarded by an os.name check in the build scripts.
- Everything else - `core`, `desktop`, and the `preview` JVM target of `mobile` (which compiles the entire shared UI) - builds and tests on this machine. `gradlew build` must stay green here.
- Toolchain: Gradle 9.3 (wrapper), Kotlin 2.2.20, JDK 25 installed (bytecode targets 17). Versions live in `gradle/libs.versions.toml`.

## Commands

```
gradlew build                 # everything buildable on this host + all tests
gradlew :core:jvmTest         # core tests (includes client-vs-real-server integration tests)
gradlew :desktop:test         # desktop tests (includes WebSocket server integration tests)
gradlew :mobile:previewTest   # mobile shared-code tests on the JVM
gradlew :desktop:run --args="run -- <command>"   # run the watcher
gradlew :mobile:run           # phone UI in a desktop window (preview target)
```

## Architecture notes

- One JSON object per WebSocket text frame; sealed `WatcherMessage` hierarchy with `type` discriminator. Clients get `hello` + history replay + last status before live messages; `SharedFlow.onSubscription` in `WatcherServer` guarantees the no-gap handoff between replay and live.
- Hooks: `Trigger` and `Action` are sealed serializable hierarchies in `core .../hooks/HookModel.kt`. `HookEngine` (common code, fully unit tested) does the matching; platform side effects live behind `ActionExecutor`. To add a trigger or action: new `@SerialName`-annotated subclass, then handle it in `HookEngine.handle` or `DesktopActionExecutor` respectively. Update `README.md` tables and `hooks.example.json` when doing so.
- `HookEngine` and `LineRingBuffer` are deliberately not thread safe; `WatcherSession` serialises access with a mutex. Keep it that way rather than adding locking inside core.
- Auth failures close the socket with code 4401 (`Protocol.CLOSE_INVALID_TOKEN`); the client treats that as fatal and stops reconnecting. Any other drop retries every 3 s.
- Mobile logic is kept out of the UI: `WatcherClient` (core) handles connection/reconnect/buffering, `AppViewModel` + `ConnectForm` (mobile commonMain, non-Compose) are unit tested with a fake `WatcherClientApi`. Prefer extending these over putting logic in composables.

## Testing conventions

- Integration tests use real sockets on port 0 (ephemeral) and real child processes; they are written to be race-free (subscribe-then-emit, `CoroutineStart.UNDISPATCHED`, generous `withTimeout`s) - follow the same patterns and avoid `delay`-based synchronisation.
- Process tests pick `cmd.exe /c` vs `/bin/sh -c` by `os.name`; keep new process-facing tests cross-platform the same way.
- kotlin-test with JUnit 5 on JVM targets. Common (multiplatform) tests go in `commonTest` so future targets inherit them; JVM-only test dependencies (Ktor server) go in `jvmTest`.
