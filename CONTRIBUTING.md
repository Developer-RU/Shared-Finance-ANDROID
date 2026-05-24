# Contributing to Shared-Finance-ANDROID

Thank you for your interest in improving Shared-Finance-ANDROID.

## Scope

This repository contains the standalone Android implementation.
iOS is maintained in a separate project.

## How to contribute

1. Fork the repository.
2. Create a branch from `main`.
3. Implement changes and tests where possible.
4. Verify build and core app flows.
5. Open a pull request with rationale and testing notes.

## Development setup

1. Install Android Studio and Android SDK.
2. Ensure Java and Gradle wrapper are available.
3. Build the app:
   - `./gradlew :app:assembleDebug`
4. Run on emulator or physical Android device.

## Coding standards

- Use idiomatic Kotlin and Compose best practices.
- Keep MVVM responsibilities explicit.
- Prefer deterministic business logic and clear state flow.
- Preserve offline-first assumptions.
- Keep BLE sync scoped to selected projects only.

## Pull request checklist

- [ ] Build is successful.
- [ ] Changes are documented when needed.
- [ ] No regressions in key user flows.
- [ ] Architecture consistency is preserved.

## Reporting bugs

Please include:
- Android version and device model.
- Reproduction steps.
- Expected and actual behavior.
- Logs/screenshots if available.
