# Publishing Guide (Android Repository)

## Goal

Prepare this Android repository for public GitHub release with complete legal, technical, and contributor-facing documentation.

## Required repository files

- `README.md`
- `DOCUMENTATION.md`
- `ARCHITECTURE.md`
- `LICENSE` (MIT)
- `CONTRIBUTING.md`
- `CODE_OF_CONDUCT.md`
- `SECURITY.md`
- `CHANGELOG.md`

## Pre-publish checklist

- [ ] Remove secrets and local-only credentials.
- [ ] Ensure no private datasets are committed.
- [ ] Verify Gradle sync/build from clean clone.
- [ ] Validate core flows in debug build.
- [ ] Confirm documentation reflects current state.
- [ ] Create initial tagged release.

## Suggested release process

1. Freeze release scope.
2. Update `CHANGELOG.md`.
3. Run final build and smoke tests.
4. Merge release branch.
5. Publish GitHub release with notes.
