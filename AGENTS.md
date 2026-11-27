# ThunBudget Analytics — Agent Working Agreement

## Project identity and product constraints
- ThunBudget Analytics is an Android-first, offline-first personal/family budget application.
- The local on-device database and normal offline operation are the product baseline. Do not introduce mandatory cloud, network, account, or sync dependencies unless the user explicitly approves that product change.
- Treat personal financial records as sensitive. Do not send transaction amounts, descriptions, categories, backups, or identifying financial data to external analytics or research services.
- Preserve existing user data, backup/restore behavior, and the established release-signing identity.

## Mandatory tool-assisted workflow
Use the best available tools for each task; do not invoke tools mechanically when they add no value.
- Codex Dev Workflows: use bug investigation for defects, feature development for scoped changes, code review for diffs, comprehensive QA for broad verification, and pre-release review before releases.
- Code Assistant: use its relevant coding, architecture, Git, debugging, and testing guidance when it improves the task.
- Codex Coordinator: use only in a Codex environment that actually supports coordinated native tasks, and only for 2–3 substantial independent workstreams. Do not claim parallel execution when it did not occur.
- Data: independently validate financial definitions, calculations, comparisons, forecasts, and charts when data or calculation correctness is material.
- Product Design and Figma: use for UX audits, design-system inspection, design comparison, and interface work when access and a visual target are available.
- Exa: use for current technical documentation, upstream issues, and research when external evidence can resolve uncertainty.
- GitHub and Python: use repository evidence, CI artifacts/logs, and reproducible independent calculations where appropriate.
- State clearly which tools and checks were actually used. A tool being installed does not prove that an account, device, or service is connected.

## Repository and change safety
- Before editing, inspect the current branch, working tree/state available through the host, recent commits, repository instructions, relevant code, and active CI runs.
- Do not assume `main` is current. Establish the authoritative implementation branch from recent commits, release evidence, and the user's current direction before writing.
- Never merge, rebase, force-push, delete branches, or rewrite history without explicit user authorization.
- Do not overwrite or discard existing work. Avoid broad staging or unrelated refactors.
- Prefer small, cohesive, reviewable changes. Keep one clear owner per file or conflicting resource.
- Before substantial changes, identify a known-good commit/build as a recovery point. Do not label a build as a baseline unless its identity and evidence are verified.
- Preserve approved functionality and UI patterns. If a requested change conflicts with a prior approved behavior/design, surface the conflict before changing it.

## Financial logic quality bar
- Treat financial calculations as correctness-critical.
- Write down the business definition, input grain, date boundaries, units, denominator, rounding policy, and expected output before changing formulas.
- Independently recompute important calculations with test fixtures or Python rather than relying only on the application's own helper functions.
- Cover normal, boundary, empty, missing, negative, partial-period, leap-year/month-boundary, duplicate, and carryover cases where relevant.
- Explicitly test the reporting-period rules, monthly aggregation, carryover/negative balances, category allocations, forecast inputs, and reconciliation between cards, history, analytics, charts, and exports.
- Distinguish zero from missing data. Never silently substitute one for the other.
- Do not claim analytical correctness based only on screenshots or successful compilation.

## Android, UI, and compatibility
- Keep Android as the primary platform. Use the user's specified primary device for manual acceptance; currently Google Pixel 8 is the primary device for app verification.
- Do not spend time prioritizing other devices when the user has explicitly deferred them, but retain automated compatibility coverage where available and authorized.
- Check light and dark themes, dialogs, navigation, keyboard/input behavior, accessibility, screen resizing, lifecycle, and persistence for UI changes.
- Use Figma as a reference only when the relevant file/frame and permissions are accessible. Do not invent design specifications.

## Testing and release gates
- Discover project-defined checks from Gradle files, tests, and GitHub Actions; do not assume undocumented commands.
- Run the narrowest relevant unit/integration/UI tests after each change, then broader regression checks for shared or high-risk logic.
- A green build proves only that the configured build steps passed. It does not prove full user-flow, device, visual, data, or release correctness.
- For a Release APK, verify the exact commit/branch, versionName/versionCode, artifact identity, persistent signing certificate, CI test results, and artifact download availability.
- Do not call a debug APK a release build or imply it can upgrade an installation signed with a different key.
- Do not publish, distribute, upload to stores, change production services, or modify cloud databases unless the user explicitly authorizes that action.

## Reporting standard
For every substantial task, report:
1. What changed and why.
2. Exact branch and commit (when repository changes were made).
3. Files/components affected.
4. Tests/checks actually run and their results.
5. What was not tested and why.
6. Remaining risks, blockers, and rollback/recovery point.

Never claim a fix, test, integration, parallel task, device test, or release verification that did not actually happen. Separate confirmed evidence from hypotheses.
