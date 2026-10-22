# Repository Guidelines

## Core Principles

- Read the relevant code, configuration, documentation, and existing conventions before making changes. Do not rewrite existing behavior based on assumptions.
- Keep changes focused on the requested outcome. Do not mix unrelated cleanup, refactoring, or formatting with functional changes.
- Preserve and respect existing user changes in the working tree.
- When requirements, scope, or destructive effects are unclear, explain the risk and ask for confirmation before proceeding.

## Branches and Git

- Develop on a dedicated branch; do not modify `main` directly.
- Use branch names in the form `feature/<topic>`, `fix/<topic>`, `refactor/<topic>`, `docs/<topic>`, or `chore/<topic>`.
- Do not overwrite the working tree, rewrite shared history, force-push, or delete branches unless the user explicitly authorizes the action.
- Keep each commit focused. Separate unrelated formatting, refactoring, and functional changes.

### Commit Messages

Use Conventional Commits:

```text
<type>(<scope>): <concise summary>

- Change 1
- Change 2
- Validation or important limitation
```

- Common types: `feat`, `fix`, `refactor`, `docs`, `test`, `build`, `ci`, `chore`, and `perf`.
- Write a clear outcome in the subject. Use the body to describe key changes and validation when useful.

## Code Quality

- Follow the repository's existing language, naming, directory, and formatting conventions.
- Keep files, classes, and functions focused on a clear responsibility. Avoid unnecessary abstractions and duplicated logic.
- Use named constants for thresholds, configuration values, and states; avoid unexplained magic numbers and strings.
- Give resources, tasks, listeners, and concurrent work explicit creation, cancellation, and cleanup paths.
- Do not silently ignore errors. Define a recovery, fallback, propagation, or user-facing failure path as appropriate.

## Comments and Logging

- Comments should explain intent, constraints, and non-obvious decisions; do not restate the code.
- Document public interfaces, complex control flow, state transitions, and important boundaries when needed for maintenance.
- Log errors with enough context to diagnose them, without exposing secrets or sensitive user data.

## Validation and Delivery

- Run compilation, tests, or manual verification appropriate to the risk of the change.
- Before committing, run formatting checks when available and inspect the final diff.
- In the final handoff, state the changes made, validation performed, and any known limitations or remaining risks.
