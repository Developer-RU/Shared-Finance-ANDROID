# Shared-Finance-ANDROID

Short description: Android app for offline shared expense tracking with project-based accounting and Bluetooth sync.

> Free and open-source software. You can use, modify, and distribute this project under the MIT License.
>
> Companion repositories:
> - Android: https://github.com/Developer-RU/Shared-Finance-ANDROID
> - iOS: https://github.com/Developer-RU/Shared-Finance-IOS

![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)
![Platform: Android](https://img.shields.io/badge/Platform-Android-3DDC84)
![Cross-Platform Sync](https://img.shields.io/badge/Sync-Android%20%E2%86%94%20iOS-blue)
![Offline First](https://img.shields.io/badge/Mode-Offline--First-orange)

Offline-first Android app for shared expense tracking between multiple participants, organized by independent projects, with selective Bluetooth synchronization.

## Table of Contents

- [Overview](#overview)
- [Who This App Is For](#who-this-app-is-for)
- [Core Features](#core-features)
- [Project-Scoped Bluetooth Sync](#project-scoped-bluetooth-sync)
- [Cross-Platform Compatibility](#cross-platform-compatibility)
- [Tech Stack](#tech-stack)
- [Project Structure](#project-structure)
- [Getting Started](#getting-started)
- [Build and Test](#build-and-test)
- [Privacy and Security](#privacy-and-security)
- [Open Source and Contribution](#open-source-and-contribution)
- [Documentation](#documentation)
- [License](#license)

## Overview

Shared-Finance-ANDROID is designed for groups that need transparent shared expense accounting without relying on cloud-only infrastructure. The app uses a project-first model to keep contexts separated.

Each project has:

- its own participants;
- its own expenses;
- its own balance calculations;
- its own history and sync context.

## Who This App Is For

- Families tracking household spending.
- Friends sharing travel and trip costs.
- Roommates splitting rent and utilities.
- Small teams managing project operational expenses.
- Any group needing local-first, transparent multi-user spending records.

## Core Features

- Multi-project expense management with strict project isolation.
- Participant management per project.
- Detailed expense records with category, amount, payer, title, notes, and date.
- Automatic balance calculation between participants.
- Change history tracking.
- Offline-first local data flow.
- BLE peer-to-peer synchronization.
- Selective synchronization of only user-selected projects.
- Conflict detection and resolution workflows.

## Project-Scoped Bluetooth Sync

Shared-Finance-ANDROID supports selective data transfer by design.

Examples:

- Sync only a "Vacation 2026" project with travel participants.
- Keep private family expenses out of work-related exchanges.
- Share one active project without transferring the full local dataset.

This model improves practical privacy control while keeping synchronization fast and focused.

## Cross-Platform Compatibility

Shared-Finance-ANDROID data structures and synchronization payloads are designed to be compatible across platforms.

Supported synchronization scenarios:

- Android to Android;
- iOS to iOS;
- Android to iOS and iOS to Android.

This enables mixed-device teams and families to collaborate in one consistent shared-expense workflow.

## Tech Stack

- Kotlin
- Jetpack Compose
- MVVM
- Coroutines / Flow
- Room (SQLite)
- Bluetooth (BLE)

## Project Structure

- `app/src/main/java` - Android source code.
- `app/src/main/res` - resources, themes, and localization files.
- `app/src/test` - unit tests.
- `app/src/androidTest` - instrumentation tests (if configured).

## Getting Started

1. Install Android Studio and required SDK components.
2. Build the debug app:
   - `./gradlew :app:assembleDebug`
3. Run on emulator or physical Android device.

## Build and Test

- Build: `./gradlew :app:assembleDebug`
- Unit tests: `./gradlew :app:testDebugUnitTest`
- Instrumentation tests (if available): `./gradlew :app:connectedDebugAndroidTest`

## Privacy and Security

- Core flows are local-first and do not require cloud sync.
- Device-to-device exchange uses BLE.
- Users choose which projects are included in sync operations.

For vulnerability reporting process, see `SECURITY.md`.

## Open Source and Contribution

- Contribution guidelines: `CONTRIBUTING.md`
- Community standards: `CODE_OF_CONDUCT.md`
- Release notes policy: `CHANGELOG.md`
- Security policy: `SECURITY.md`

## Documentation

- `DOCUMENTATION.md` - detailed product and functional documentation.
- `ARCHITECTURE.md` - architecture and technical boundaries.
- `PUBLISHING.md` - public repository and release process.
- `CHANGELOG.md` - user-visible change history.

## License

Licensed under the MIT License. See `LICENSE`.
