# Terminal Watcher

Terminal Watcher lets you follow the output of a terminal command from your phone. A small desktop application wraps or receives the output of any command and serves it over your local network. A mobile viewer app (Android and iOS, one shared Kotlin codebase) streams that output live, and a configurable hook system fires actions when things happen: push a notification to your phone when the build fails, call a webhook when the process exits, run a follow-up command when a log line matches a pattern.

Typical uses: kick off a long build or test run, walk away, and get notified on your phone the moment it errors, finishes, or goes quiet.

## How it works

```
+----------------------+        WebSocket (JSON, LAN)        +---------------------+
|  desktop watcher     | ----------------------------------> |  mobile viewer app  |
|  wraps your command  |   output lines, status, alerts      |  Android / iOS      |
|  runs hook engine    |                                     |  local notifications|
+----------------------+                                     +---------------------+
        |
        +--> hooks: notify viewers, POST webhooks, run commands
```

The desktop watcher captures stdout and stderr of a command (or reads piped stdin), keeps a rolling history buffer, and serves everything over an embedded WebSocket server protected by a token. Phones on the same network connect directly to your machine; no cloud service or account is involved. Notifications are delivered over the open connection and raised as local notifications by the app.

## Project layout

| Module    | What it is |
|-----------|------------|
| `core`    | Kotlin Multiplatform library shared by both sides: wire protocol, hook model and engine, line buffer, and the reconnecting WebSocket client used by the app. |
| `desktop` | The command line watcher application (Kotlin/JVM, Ktor server). |
| `mobile`  | The viewer app (Compose Multiplatform: Android, iOS, plus a desktop preview target for development). |
| `iosApp`  | Swift entry point for the iOS application shell. |

## Requirements

- JDK 17 or newer to build and run the desktop watcher (the repo was developed against JDK 25 and Gradle 9.3 via the included wrapper).
- Android SDK only if you want to build the Android app.
- macOS with Xcode only if you want to build the iOS app.

The core and desktop modules, the full test suite, and a desktop preview of the mobile UI all build on any platform with just a JDK.

## Quick start

### 1. Start the desktop watcher

Wrap a command so its output is captured and mirrored to your normal terminal:

```
gradlew :desktop:run --args="run -- gradle build --info"
```

Or install a distribution once and use the plain executable:

```
gradlew :desktop:installDist
desktop/build/install/terminal-watcher/bin/terminal-watcher run -- gradle build
```

To have `terminal-watcher` available everywhere, copy the distribution somewhere stable and add its `bin` directory to your PATH (the `build` directory is deleted by `gradlew clean`). On Windows, in PowerShell:

```
gradlew :desktop:installDist
Copy-Item desktop\build\install\terminal-watcher "$env:LOCALAPPDATA\Programs\terminal-watcher" -Recurse
[Environment]::SetEnvironmentVariable("Path",
    [Environment]::GetEnvironmentVariable("Path", "User") + ";$env:LOCALAPPDATA\Programs\terminal-watcher\bin", "User")
```

New terminals will then resolve `terminal-watcher` directly. Repeat the installDist and copy steps to pick up code changes.

Alternatively, pipe any output into it:

```
some-long-command | terminal-watcher pipe
```

On startup the watcher prints everything the phone needs:

```
[terminal-watcher] watching: gradle build
[terminal-watcher] hooks loaded: 2
[terminal-watcher] token: 3f9c2a417b6d
[terminal-watcher] connect the app to: 192.168.1.23 port 8765
```

Options:

```
--port <n>       Port to serve on (default 8765, 0 picks a free port)
--bind <host>    Address to bind (default 0.0.0.0)
--token <t>      Access token clients must present (default: randomly generated)
--hooks <path>   Path to a hooks JSON file (default: hooks.json if present)
--history <n>    Number of recent lines replayed to new clients (default 2000)
```

On Windows, commands that are batch scripts or shell builtins (`gradle`, `npm`, `mvn`, `echo`, ...) are automatically run through `cmd.exe`, so `run -- gradle build` behaves the same as typing `gradle build` in a terminal.

### 2. Connect from your phone

Open the viewer app, enter the host, port and token from the banner, and tap Connect. You will see the recent history immediately, then live output as it happens. The app reconnects automatically if the connection drops. Your phone must be on the same network as the desktop machine.

To try the viewer UI without a phone, run the desktop preview:

```
gradlew :mobile:run
```

### 3. Set up hooks

Create a `hooks.json` next to where you start the watcher (or pass `--hooks <path>`). A hook is a trigger plus one or more actions. Full example in `hooks.example.json`:

```json
{
  "hooks": [
    {
      "name": "error-alert",
      "trigger": { "type": "output-matches", "pattern": "(?i)error", "stream": "STDERR" },
      "actions": [
        { "type": "notify", "title": "Error", "body": "{line}" }
      ]
    },
    {
      "name": "finished",
      "trigger": { "type": "process-exits", "codes": [1, 2] },
      "actions": [
        { "type": "webhook", "url": "https://example.com/alert" }
      ]
    }
  ]
}
```

#### Triggers

| Type | Fields | Fires when |
|------|--------|------------|
| `output-matches` | `pattern` (regex), optional `stream` (`STDOUT`, `STDERR`, `SYSTEM`) | a captured line matches the pattern |
| `process-exits` | optional `codes` (list of exit codes; omit for any) | the watched process exits |
| `inactivity` | `seconds` | no output has appeared for that long; re-arms when output resumes |

#### Actions

| Type | Fields | Effect |
|------|--------|--------|
| `notify` | `title`, `body` | sends a notification to all connected phones, raised as a local push notification |
| `webhook` | `url` | POSTs a JSON payload with the hook name and context values |
| `run-command` | `command` | runs a shell command on the desktop machine |

#### Hook options and templates

Every hook takes optional `enabled` (default true) and `once` (default false; fire at most once per session). Action strings support `{placeholder}` templates:

- `output-matches` provides `{line}`, `{match}`, `{stream}`, and `{group1}`, `{group2}`, ... for regex capture groups.
- `process-exits` provides `{exitCode}`.
- `inactivity` provides `{idleSeconds}`.
- `{hook}` (the hook name) is always available.

## Building the mobile app

### Android

Install the Android SDK (or Android Studio) so that `ANDROID_HOME` is set or `local.properties` contains `sdk.dir`. The Android target of `:mobile` is enabled automatically when an SDK is detected. Then:

```
gradlew :mobile:assembleDebug
```

Install the resulting APK from `mobile/build/outputs/apk/debug/`. On first launch the app asks for notification permission; grant it so hook notifications appear.

### iOS

iOS builds require macOS. The `:mobile` module automatically enables its iOS targets on a Mac and produces a `MobileApp` framework. Create an iOS App project in Xcode (or use the Kotlin Multiplatform wizard), add the source in `iosApp/iosApp/iOSApp.swift`, and add a build phase that runs:

```
./gradlew :mobile:embedAndSignAppleFrameworkForXcode
```

The Swift shell in `iosApp/` shows exactly how the shared UI is mounted (`MainViewControllerKt.MainViewController()`).

## Extending the event system

Triggers and actions are sealed, serializable hierarchies in `core/src/commonMain/kotlin/com/tward/watcher/core/hooks/HookModel.kt`, so both sides of the wire and the JSON config format extend the same way:

1. Add a new data class implementing `Trigger` or `Action` with a `@SerialName("your-type")` discriminator.
2. For a trigger, handle it in `HookEngine.handle` (add a new `WatcherEvent` if it needs a new input signal).
3. For an action, handle it in `DesktopActionExecutor` (or another `ActionExecutor` implementation).
4. Use it in `hooks.json` with `"type": "your-type"`.

Ideas that fit this model directly: a trigger for a line count threshold, an action that sends email, or an action that posts to a chat service.

## Security notes

- The token is required on every WebSocket connection; connections with a wrong token are closed with a dedicated close code and the app stops retrying.
- Traffic is plain `ws://` intended for trusted local networks. Do not expose the port to the internet as-is; if you need remote access, tunnel it (for example over SSH or a VPN).
- `run-command` hooks execute arbitrary shell commands on your machine; treat your hooks file with the same care as a shell script.

## Protocol

One JSON object per WebSocket text frame, discriminated by a `type` field. Clients authenticate with `?token=` on the upgrade request to `/ws`. Server messages: `hello` (session metadata), `output` (batch of lines with sequence numbers, stream and timestamp), `status` (process running or exited), `notification` (fired hook). New clients receive `hello`, the replayed history, and the current status before live messages. See `core/src/commonMain/kotlin/com/tward/watcher/core/protocol/Protocol.kt`.

## Development

```
gradlew build          # compile everything buildable on this machine and run all tests
gradlew :core:jvmTest      # core unit and integration tests
gradlew :desktop:test      # desktop unit and integration tests
gradlew :mobile:previewTest  # mobile shared-code tests (JVM)
```

The test suite covers the hook engine, template rendering, protocol round-trips, config parsing, the ring buffer, CLI parsing, real child-process capture, the WebSocket server end to end, the reconnecting client against a live server, and the app view model.

## Current limitations and roadmap

- Notifications are delivered over the open WebSocket connection, so the app must be running (foreground, or background for as long as the OS keeps the socket alive). True offline push (FCM and APNs) needs a relay backend and is the natural next step; the `notify` action is already the single place such a relay would plug in.
- One watched command per watcher process. Run several watchers on different ports to follow several commands.
- The viewer is read-only by design; sending input to the terminal is out of scope for now.
