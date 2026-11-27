# Balance Pro Visual Refresh Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Implement the approved Balance Pro visual system across the existing ThunBudget Android screens without changing navigation, screen structure, business logic or calculations.

**Architecture:** Keep the current Jetpack Compose / Material 3 composition. Add a single palette token object, reuse it from the app theme and shared dialog theme, then apply the same tokens to existing cards, icons and chart drawing. No view-model, repository, formula or data-model changes.

**Tech Stack:** Kotlin 2.0.21, Jetpack Compose BOM 2024.12.01, Material 3, Material Icons Extended, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-11-27-balance-pro-visual-refresh-design.md`

## Global Constraints
- Android-first and offline-first; no network, cloud, accounts or telemetry.
- Keep all financial data and logic local.
- Do not alter any formula, date boundary, filter predicate, persistence key, callback, navigation destination ID or screen order.
- Preserve dark modal #21222D and field #343440.
- Do not merge to main, publish to stores, or release outside the branch.

## Review Focus
- Dark theme keeps graphite dialogs and input fills, uses the calm violet accent #A58EC6, and applies a violet-tinted surface hierarchy.
- Category colors remain consistent between overview, period, month and annual charts.
- Empty and single-category charts render without divide-by-zero or missing legend labels.
- Long category names and large ruble values remain readable on compact screens.
- Reordered navigation and customized overview order remain untouched.

---

### Task 1: Palette tokens and tests

**Files:**
- Create: `app/src/main/java/ru/scooblydoon/thunbudget/ThunBudgetPalette.kt`
- Create: `app/src/test/java/ru/scooblydoon/thunbudget/ThunBudgetPaletteTest.kt`

**Interfaces:**
- Produces: `ThunBudgetPalette.Light`, `ThunBudgetPalette.Dark`, `ThunBudgetPalette.ChartSeries` and typed `ThunBudgetThemePalette`.

- [ ] Extract existing theme values into a single token object.
- [ ] Add unit assertions for the approved light/dark colors, dialog colors and chart-series colors.
- [ ] Verify red/green behavior with `gradle :app:testDebugUnitTest`.

### Task 2: Shared theme and dialog surface

**Files:**
- Modify: `app/src/main/java/ru/scooblydoon/thunbudget/MainActivity.kt`
- Modify: `app/src/main/java/ru/scooblydoon/thunbudget/DialogTheme.kt`
- Modify: `app/src/main/java/ru/scooblydoon/thunbudget/FlexiblePeriodsScreen.kt`
- Modify: `app/src/androidTest/java/ru/scooblydoon/thunbudget/DialogThemeScreenshotTest.kt`

- [ ] Build Material 3 light/dark schemes from the palette tokens.
- [ ] Keep the light modal white and dark modal graphite; theme input fills consistently.
- [ ] Replace the flexible-period editor's direct AlertDialog with AppAlertDialog and themed fields.
- [ ] Keep all current dialog copy, validation and callbacks unchanged.

### Task 3: Navigation, quick actions and overview

**Files:**
- Modify: `app/src/main/java/ru/scooblydoon/thunbudget/MainActivity.kt`

- [ ] Replace text glyphs in the existing six bottom-navigation destinations with outlined icons, preserving IDs, labels, order and drag-reorder behavior.
- [ ] Replace theme/header/quick-action glyphs and edit/search affordance symbols with Material outlined icons.
- [ ] Apply the approved card surfaces, semantic state colors and light/dark budget-card treatment to the four existing Overview blocks.
- [ ] Preserve default block order `period, budget, income, categories` and the saved custom order behavior.

### Task 4: Analytics, charts and existing secondary screens

**Files:**
- Modify: `app/src/main/java/ru/scooblydoon/thunbudget/MainActivity.kt`
- Modify: `app/src/main/java/ru/scooblydoon/thunbudget/FlexiblePeriodsScreen.kt`
- Modify: `app/src/main/java/ru/scooblydoon/thunbudget/RecurringPaymentsScreen.kt`

- [ ] Reuse the same category palette in period, month, year and category breakdown charts.
- [ ] Keep currency, percentage, period and axis labels; refine chart grid and bar shapes only.
- [ ] Apply the shared surfaces, borders and semantic status colors to existing analytics, history, flexible-period and recurring-payment cards.
- [ ] Do not change chart inputs, selected-category state, aggregation, forecast or date predicates.

### Task 5: Verification and handoff

**Files:**
- Review: all changed source/test files and workflow artifacts.

- [ ] Run `gradle :app:testDebugUnitTest` in GitHub Actions.
- [ ] Verify Release APK build, persistent certificate fingerprint and artifact presence.
- [ ] Review available screenshot tests and rendered evidence; distinguish emulator checks from manual Pixel 8 acceptance.
- [ ] Confirm diff contains no edits to financial calculators, repositories, date rules or callback behavior.
- [ ] Open a draft PR for review; do not merge or distribute the APK without a separate user instruction.
