# Shared-Finance-ANDROID Documentation

## 1. Product Purpose

Shared-Finance-ANDROID is an offline-first shared expense tracking application for multiple participants, organized by independent projects.

Representative scenarios:

- family finance tracking;
- trip and vacation budgets;
- apartment and utility sharing;
- work project expense coordination;
- any small-group shared spending workflow.

## 2. Core Concept

Each project is an isolated accounting context with:

- independent participants;
- independent expense records;
- independent balance calculations;
- independent history and synchronization state.

This prevents accidental mixing of unrelated contexts such as household and travel spending.

## 3. Functional Scope

### 3.1 Projects

- Create, edit, and manage multiple projects.
- Switch quickly between contexts.
- Keep all calculations strictly project-scoped.

### 3.2 Participants

- Maintain participant lists per project.
- Link expenses to specific payers.
- Use participant identity in balance and history logic.

### 3.3 Expenses

- Add expenses with amount, category, title, notes, and date.
- Edit and delete expense entries.
- Preserve explicit project and participant references.

### 3.4 Balances

- Automatically compute who owes whom.
- Recalculate balances after each relevant change.
- Present settlement-friendly balance outputs.

### 3.5 History

- Track major user-visible operations.
- Improve auditability and team trust.

### 3.6 BLE Synchronization

- Peer-to-peer synchronization between nearby devices.
- Transfer only selected projects.
- Support practical offline sharing without cloud dependency.

### 3.7 Conflict Resolution

- Detect record conflicts during sync merge.
- Provide local-vs-remote decision paths.
- Support deterministic merge behavior and future policy extension.

## 4. Why Selective Project Sync Is Important

Selective synchronization enables real-world privacy and workflow control:

- share only the project relevant to a specific group;
- keep personal or family projects private;
- sync work-related data without exposing unrelated contexts.

## 5. Technology Stack

- Kotlin
- Jetpack Compose
- MVVM
- Coroutines / Flow
- Room (SQLite)
- BLE synchronization components

## 6. Architecture and Codebase Layout

- `app/src/main/java` - source code.
- `app/src/main/res` - resources and localization.
- `app/src/test` - unit tests.
- `app/src/androidTest` - instrumentation tests (if configured).

The current implementation follows a production-oriented architecture and can be incrementally extended while preserving stability and maintainability.

## 7. Data Storage and Offline Model

- Local persistence via Room over SQLite.
- Offline-first behavior as primary operating mode.
- Migration strategy prepared for schema evolution.

## 8. Build Instructions

1. Install Android Studio and required SDK components.
2. Build the application:
   - `./gradlew :app:assembleDebug`

## 9. Cross-Platform Synchronization Compatibility

Synchronization contracts are designed for cross-platform data interoperability.

Supported scenarios:

- Android to Android.
- iOS to iOS.
- Android to iOS and iOS to Android.

Data entities and sync payload formats remain structured and compatible across both platforms.

## 10. GitHub Publication Assets

Public repository readiness files:

- `README.md`
- `ARCHITECTURE.md`
- `LICENSE` (MIT)
- `CONTRIBUTING.md`
- `CODE_OF_CONDUCT.md`
- `SECURITY.md`
- `PUBLISHING.md`
- `CHANGELOG.md`
