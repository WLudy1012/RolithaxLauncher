<div align="center">

<img src="./app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" alt="Rolithax Launcher" width="96">

# Rolithax Launcher

**One app to manage remote MSLX Daemons and Minecraft Java servers running on your phone.**

[![License: AGPL v3](https://img.shields.io/badge/License-AGPL%20v3-blue.svg)](LICENSE)
[![Android CI](https://github.com/WLudy1012/RolithaxLauncher/actions/workflows/android.yml/badge.svg)](https://github.com/WLudy1012/RolithaxLauncher/actions/workflows/android.yml)
[![Release](https://img.shields.io/github/v/release/WLudy1012/RolithaxLauncher?include_prereleases&label=release)](https://github.com/WLudy1012/RolithaxLauncher/releases)
![Android 7.0+](https://img.shields.io/badge/Android-7.0%2B-3DDC84)

[简体中文](README.md) · [繁體中文](README.zh-TW.md) · **English**

</div>

Rolithax Launcher is an independent, **third-party** Android client for [MSLX Daemon](https://github.com/MSLTeam/MSLX), built with Kotlin and Jetpack Compose.
It puts "manage instances on a remote Daemon" and "host a server directly on your phone" into one instance list and one console, with a workflow designed for touch screens.

> This app is not affiliated with MSLTeam in any way. Please read the [Disclaimer](#disclaimer) before use.
> Application ID: `com.wludy.rolithax.launcher`. The package name and signing key changed, so older builds must be installed fresh; migrate old data with the in-app encrypted export/import.

This app integrates the MSLAPI download service: **本服务由MSL开服器提供** ("This service is provided by MSL Server Launcher"). <a href="https://mslmc.cn"><img src="./app/src/main/res/drawable-nodpi/msl_logo.png" alt="MSL logo" width="32"></a> [mslmc.cn](https://mslmc.cn)

## Download

Get it from [GitHub Releases](https://github.com/WLudy1012/RolithaxLauncher/releases) or the [CNB mirror](https://cnb.cool/WLudy/RolithaxLauncher/-/releases) (faster in mainland China). Every release ships two packages:

| Package | Description |
| --- | --- |
| `app-release.apk` (full) | Bundles a Java 17 runtime, so local hosting works offline |
| `app-release-lite.apk` (lite) | Smaller; the first local server needs a ~36 MB runtime download |

Both are signed with the same key and can be installed over each other. The app can also check for and install updates itself, see [Updates and versioning](#updates-and-versioning).

## Features

**Connect and manage**
- Save multiple Daemons at once. Everything is addressed by **instance**, and local instances are merged with each Daemon's instances in a single list.
- Pair by scanning the QR code shown on the Daemon's web page. Pairing relies on this project's [Daemon plugin](https://github.com/WLudy1012/Rolithax-MSLX-plugin); you can also enter the address and API Key manually.
- Start, stop, restart, force-kill and back up; the EULA prompt guides you when it is not accepted yet. Instances can be pinned, and deletion needs a second confirmation.

**Console**
- Live logs over SignalR with incremental ANSI color and style parsing; send commands, auto-scroll, jump to latest.
- Remote and local instances share the same console UI.

**Create and Resource Center**
- The creation wizard covers basics, server core, Java environment, resources and confirmation, with an online core catalog, remote download, local upload and modpacks.
- Resource Center: browse Modrinth mods, plugins, modpacks and data packs, filtered by the target instance's core and game version; dependencies and conflicts are checked together before a single install.
- For a remote Daemon, the machine running the Daemon downloads and installs directly; your phone does not relay the files.

**Local hosting**
- Run a Minecraft Java server on the phone without a Daemon, Termux or proot. See [Local hosting](#local-hosting).

**Settings and security**
- File manager, plugin and mod management, `server.properties` editor, encrypted backup and migration.
- Frosted-glass UI, dynamic color and custom backgrounds; dark theme; a navigation rail on landscape and tablets.
- Runtime logs can be viewed, copied and exported with automatic redaction; after a crash you can file an Issue in one tap.

## Quick start

**Connect to a remote Daemon**

1. Start MSLX Daemon on your computer and have its address and API Key ready.
2. In the app, open "Connect Daemon", enter the address (for example `https://192.168.1.100:1027`) and the API Key, then connect.
   If the Rolithax plugin is installed, you can scan the QR code on its page instead.
3. Open the instance list, tap an instance to view logs, send commands, start or stop it.

**Host a server on your phone**

1. Tap the "+" in the middle of the bottom Dock and choose "Local" as the target.
2. Pick a server core and version and finish the wizard; a Java runtime is installed on first use if needed.
3. Start the instance and use the console as usual.

## Local hosting

Instance folders can live in two places; folder names are unique across both:

- **Public storage** (default): `/storage/emulated/0/MSLX/servers/<name>`. You can drop cores, mods and backups in with a file manager. It needs the "All files access" permission on Android 11+; without it the instance is created in private storage instead and you are told so, rather than blocking the server.
- **App-private storage**: `files/mslx/servers/<name>`. No permission needed, but files are only reachable through the in-app file manager.

Two engines:

| Engine | How it works | Limits |
| --- | --- | --- |
| Baseline (in-process JVM) | NDK `dlopen libjvm.so` and creates a JVM, no permission needed | Only one JVM per process; after stopping, fully exit the app before starting again |
| Enhanced (Shizuku) | Launches a real `java` child process with shell privileges | Needs Shizuku; supports concurrent instances, in-place restart and other Java versions, and falls back to the baseline when not authorized |

You can install several Java runtimes in Settings and choose one per instance. A version guard runs before start and blocks a runtime that is too old for the core, with a suggestion:

| Game version | Suggested runtime |
| --- | --- |
| 1.16 and below | Java 8 |
| 1.17 – 1.20.4 | Java 17 (bundled in the full build) |
| 1.20.5+ | Java 21 |
| 26.x+ | Java 25 |

Every runtime is SHA-256 verified. Downloads fall back from the CNB mirror to GitHub Releases to the upstream source, and fail closed when no trusted digest is available. A foreground service with a persistent notification keeps the server alive, and you can stop it from the notification.

## Security

- HTTPS by default: addresses without a scheme get `https://`, and plain `http://` is upgraded. Cleartext is kept only if you explicitly allow it on the connect page and accept the warning, for trusted LANs only.
- Self-signed certificates are trusted only for the Daemons you configure; MSLAPI, GitHub and other public services use the system trust chain.
- API Keys are encrypted with Android Keystore AES-GCM and excluded from system backups; in-app export/import uses a separate passphrase-encrypted archive and never exports the Keystore key.
- Downloads are streamed and digest-verified; extraction limits paths, per-file size, total size and entry count; running instances cannot be deleted or moved.
- Logs only contain redacted information, with no addresses, paths, tokens or API Keys.

## Updates and versioning

- **Stable**: tag without suffix (for example `v1.8.0`). **Beta**: tag with `-Beta`. **Forced update**: tag with `-Force`; the client cannot skip it.
- The "Update channel" setting defaults to Stable; the Beta channel receives both stable and Beta releases. Updates are downloaded, verified in the app, then handed to the system installer.
- Updates are fetched from the CNB mirror first and fall back to GitHub Releases.
- Versions are compared numerically segment by segment, so a stable release must have a higher version than the Beta with the same number, or Beta users will not receive it.

## Build

Requirements: JDK 17+, Android SDK Platform 35, NDK 28.2.13676358.

```bash
./gradlew :app:compileDebugKotlin     # compile check
./gradlew :app:testDebugUnitTest      # unit tests
./gradlew assembleDebug               # debug APK
./gradlew assembleRelease             # signed release, needs keystore.properties
./gradlew assembleRelease -PwithoutJre=true   # lite build without the bundled JRE
```

The first sync needs network access. In mainland China, set `MSLX_MAVEN_GOOGLE_MIRROR` and `MSLX_MAVEN_CENTRAL_MIRROR` to use mirror repositories.
Fetch the bundled JRE with `fetch-jre-assets.ps1` and rebuild the `LD_PRELOAD` shim with `build-shim.ps1`.

## Project layout

```text
app/src/main/java/com/wludy/rolithax/launcher/
├── RolithaxApplication.kt   Application and dependency container
├── MainActivity.kt          Single activity, disclaimer gate, update and crash entry
├── localengine/             NativeVm and JNI declarations
├── data/
│   ├── remote/              Retrofit, SignalR, network clients
│   ├── localengine/         Local instances, JRE, engines, keep-alive
│   ├── resources/           Resource providers, Room, Paging, installation
│   └── ...                  Repositories, settings, crypto, backup, logging
└── ui/                      home / instances / console / create / servers / settings / ...
app/src/main/cpp/            Native bridge for the in-process JVM
```

Architecture, API contracts, security constraints and the release process are documented in [`develop/agent/`](develop/agent/README.md) (in Chinese).

## Related repositories

- [Rolithax-MSLX-plugin](https://github.com/WLudy1012/Rolithax-MSLX-plugin): the Daemon plugin providing QR pairing, instance icons and remote resource installation.
- [RolithaxLauncher-website](https://github.com/WLudy1012/RolithaxLauncher-website): project website and download page.

## Feedback

Open an issue on [Issues](https://github.com/WLudy1012/RolithaxLauncher/issues) with your version and redacted logs (Settings → Runtime logs). After a crash the app offers to file one for you.

## License

Licensed under the [GNU AGPL-3.0](LICENSE), consistent with upstream [MSLX](https://github.com/MSLTeam/MSLX). Copyright (C) 2026 WLudy1012.
Third-party components and the bundled runtime are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md), also available offline in the app under Settings → About → Legal.

## Disclaimer

This is an **independent third-party app** and has no responsibility, affiliation, agency or subordination relationship with MSLTeam. Managing server instances may modify data, read and write files and start or stop processes; public-storage and Shizuku (shell-level) access are high privileges, and you bear all consequences. See [DISCLAIMER.txt](DISCLAIMER.txt) (in Chinese); it must be read and accepted on first launch.
