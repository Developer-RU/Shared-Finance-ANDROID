# Short Finance Android Architecture

## Overview

Short Finance Android is designed as an offline-first shared expense tracker with project-level data isolation and selective Bluetooth synchronization.

## Architectural style

- UI: Jetpack Compose.
- Presentation logic: MVVM.
- Asynchronous flows: Coroutines and Flow.
- Persistence: Room over SQLite.
- Sync: BLE service layer with merge/conflict handling.

## Core layers

- Presentation layer
  - Composable screens and reusable UI components.
  - ViewModels coordinate state and user actions.
- Domain/service layer
  - Expense operations, participant management, balance calculations.
  - Sync orchestration and conflict policies.
- Data layer
  - Repository abstractions.
  - Room entities/DAO/database.
  - Local persistence as source of truth on device.

## Data model principles

- All entities are scoped by project identity.
- Stable UUID-based identifiers for cross-device matching.
- Version and update metadata for deterministic merges.
- Change history for auditability.

## Sync model

- User-triggered peer-to-peer sync via BLE.
- Payload includes only selected projects.
- JSON serialization for transport compatibility.
- Conflict detection and user-visible resolution paths.

## Non-functional goals

- Predictable offline behavior.
- Strong separation of concerns.
- Easier testability of business and sync logic.
- Incremental evolution to iOS feature parity.
