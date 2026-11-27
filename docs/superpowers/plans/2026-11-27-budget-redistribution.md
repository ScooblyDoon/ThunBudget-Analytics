# Automatic Reporting-Period Budget Redistribution Implementation Plan

> **For agentic workers:** Use the available GitHub workflow tools and Codex Dev Workflows/Superpowers instructions. Steps use checkbox syntax for tracking.

**Goal:** Add a forecast-backed, repeat-safe, user-confirmed budget-transfer proposal for the selected ThunBudget reporting period.

**Architecture:** Keep the existing saved-period-share switch and calculations intact. Introduce a pure redistribution calculator, a separate local period-scoped transfer ledger, and an independent Compose setting whose proposal is surfaced on Overview. Derive effective category budgets from saved plan plus ledger adjustments; never create synthetic transactions.

**Tech Stack:** Kotlin 2.0.21, Android Compose Material 3, SharedPreferences/JSON, JUnit 4.13.2, existing Gradle 8.9 GitHub Actions Release workflow.

**Spec:** `docs/superpowers/specs/2026-11-27-budget-redistribution-design.md`

## Global Constraints

- Android-first, offline-first; no mandatory cloud, network, account or sync dependencies.
- Preserve local user data, backup/restore behavior and established release-signing identity.
- Do not change actual expense/income transactions to represent category-budget movements.
- Do not merge, rebase, force-push, delete branches or change `main` without explicit authorization.
- Preserve existing saved reporting-period plans and defined-zero versus missing-income behavior.
- Keep the 2% ordinary-donor safety buffer global across all ordinary donor categories.
- Release target: **1.3.45 / versionCode 58**, with the established signing certificate.
- Pixel 8 manual validation is required; no device result may be inferred from CI.

## Review Focus

1. A previously confirmed partial transfer must not be proposed/applied twice after app restart, recomposition, or toggle off/on.
2. A future rent/utility/credit/insurance payment must remain reserved when a matching future transaction is entered or later marked completed.
3. A category without meaningful usable history must not be considered a free donor solely because no spend is recorded yet.
4. A period with defined zero/negative income must not fall back to monthly income or create fictitious donor budget.
5. Manual category changes, period navigation, Undo and backup restore must preserve the correct period-scoped transfer adjustments and protection flags.

---

### Task 1: Pure redistribution calculator and behavior tests

**Files:**
- Create: `app/src/main/java/ru/scooblydoon/thunbudget/BudgetRedistribution.kt`
- Test: `app/src/test/java/ru/scooblydoon/thunbudget/BudgetRedistributionEngineTest.kt`

**Interfaces:**
- `RedistributionCategoryInput(name, plannedAmount, actualToDate, knownFutureExpenses, unpaidRecurringPayments, variableForecast, variableForecastUpper, hasEvidence, protectedFromRedistribution)`
- `BudgetRedistributionProposal(periodStart, periodEnd, fingerprint, deficits, transfers, uncoveredByCategory)`
- `BudgetRedistributionCalculator.calculate(periodStart, periodEnd, periodIncome, categories)`
- `AppliedBudgetRedistributionTransfer(periodStart, periodEnd, fromCategory, toCategory, amount, proposalFingerprint, appliedAt)`
- `BudgetRedistributionCalculator.netAdjustments(periodStart, periodEnd, appliedTransfers)`
- `BudgetRedistributionCalculator.appendAppliedTransfers(proposal, existingLedger, appliedAt)`

- [ ] **Step 1: Write failing tests** for the 946 ₽ screenshot case; reserve-first and 20/15/10/>5 donor order; savings/protected/no-history exclusions; conservative upper forecast; single global 2% buffer; partial coverage; defined zero income; cent rounding; deterministic fingerprint; duplicate application rejection; period-isolated net adjustments.
- [ ] **Step 2: Run the targeted test** `gradle :app:testDebugUnitTest --tests 'ru.scooblydoon.thunbudget.BudgetRedistributionEngineTest'`.
  Expected: test compilation or assertions fail because the new API/behavior is not implemented.
- [ ] **Step 3: Implement only the pure calculator and ledger arithmetic** in `BudgetRedistribution.kt`. Use a single 2% ordinary-donor buffer across the combined eligible balance. Calculate recipient deficits from the central forecast, donor balances from the upper forecast.
- [ ] **Step 4: Run the targeted test and full suite** `gradle :app:testDebugUnitTest`. Expected: all tests pass, including the new suite.
- [ ] **Step 5: Commit** `feat: add forecast-safe budget redistribution engine`.

### Task 2: Category protection and durable transfer ledger

**Files:**
- Modify: `app/src/main/java/ru/scooblydoon/thunbudget/XlsxExporter.kt` (`BudgetCategory` model)
- Modify: `app/src/main/java/ru/scooblydoon/thunbudget/MainActivity.kt` (`BudgetStore`, category/backup/undo persistence)
- Modify: `app/src/main/java/ru/scooblydoon/thunbudget/BudgetRedistribution.kt` (ledger codec/default protection rules)
- Test: `app/src/test/java/ru/scooblydoon/thunbudget/BudgetRedistributionPersistenceTest.kt`

**Interfaces:**
- Add `BudgetCategory.protectedFromRedistribution: Boolean = false`.
- Add `BudgetRedistributionLedgerCodec.encode/decode`.
- Add `BudgetRedistributionProtectionDefaults.isProtectedByDefault(categoryName)`.
- Add `BudgetStore.load/saveAutoRedistributionEnabled`, `loadBudgetRedistributionLedger`, and `applyBudgetRedistribution(proposal, appliedAt)`.
- Add one preference key for auto-redistribution enabled and one versioned JSON key for the applied transfer ledger.

- [ ] **Step 1: Write failing tests** for old category JSON lacking the optional field; default protected names (housing, rent, utilities, loan/credit, insurance, tax, savings); explicit true/false overrides; ledger encode/decode; invalid/duplicate rows; period separation; duplicate proposal fingerprint rejection.
- [ ] **Step 2: Run the targeted tests** `gradle :app:testDebugUnitTest --tests 'ru.scooblydoon.thunbudget.BudgetRedistributionPersistenceTest'`.
  Expected: failures for missing model/codec/store behavior.
- [ ] **Step 3: Implement model and persistence.** Preserve protection flags through category normalization and manual snapshots. Add the ledger and automation flag to Undo snapshots, manual backup whitelist/validation and automatic backups. Keep existing preferences and data readable.
- [ ] **Step 4: Run the targeted test and full suite.** Expected: old JSON without protection data still loads; all ledger round trips and validation tests pass.
- [ ] **Step 5: Commit** `feat: persist protected categories and period redistribution ledger`.

### Task 3: Forecast and recurring-commitment assembly

**Files:**
- Create: `app/src/main/java/ru/scooblydoon/thunbudget/BudgetRedistributionForecast.kt`
- Modify: `app/src/main/java/ru/scooblydoon/thunbudget/MainActivity.kt` (wire period data into assembler)
- Test: `app/src/test/java/ru/scooblydoon/thunbudget/BudgetRedistributionForecastTest.kt`

**Interfaces:**
- `BudgetRedistributionForecastAssembler.buildInputs(...)`
- `RecurringPaymentForecastCalculator.unpaidCommitments(payments, expenses, periodStart, periodEnd, today)`
- Output is `List<RedistributionCategoryInput>`; its planned amount already includes period ledger net adjustments.

- [ ] **Step 1: Write failing tests** for actual-to-date boundary; future expenses; future-only forecast fields; upper forecast for donors; recurring completed/disabled/overdue payments; one-to-one category/date/amount de-duplication; period end in the past; no-history donor evidence.
- [ ] **Step 2: Run the targeted test** `gradle :app:testDebugUnitTest --tests 'ru.scooblydoon.thunbudget.BudgetRedistributionForecastTest'`.
  Expected: targeted failures until the assembler and commitment calculator exist.
- [ ] **Step 3: Implement the assembler.** Keep actual, entered future expenses, recurring payments and variable forecast components separate until the final sum. Count matching recurring payments once. Use `forecastForPeriod` for recipient expected spend and `forecastHighForPeriod` for donor safety.
- [ ] **Step 4: Run targeted and full tests.** Expected: all inputs have exact period boundaries and no duplicate commitments.
- [ ] **Step 5: Commit** `feat: assemble period forecast and unpaid commitments`.

### Task 4: Separate automation setting and Overview proposal/apply UI

**Files:**
- Create: `app/src/main/java/ru/scooblydoon/thunbudget/BudgetRedistributionCard.kt`
- Modify: `app/src/main/java/ru/scooblydoon/thunbudget/MainActivity.kt` (independent switch, Overview card, apply/Undo state)
- Modify: `app/src/main/java/ru/scooblydoon/thunbudget/MainActivity.kt` (SettingsDialog donor protection checkbox)
- Test: `app/src/androidTest/java/ru/scooblydoon/thunbudget/BudgetRedistributionCardTest.kt` where emulator/UI-test infrastructure allows; retain pure workflow regression tests from Tasks 1–3.

- [ ] **Step 1: Write UI/workflow test coverage** for independent switch state; proposal visible in Overview; Apply performs exactly one ledger write; switch off does not clear ledger; repeated Apply and recompose/restart do not duplicate; Undo restores ledger; protected category toggle persists.
- [ ] **Step 2: Run the relevant tests** and record whether UI tests can run under the configured workflow. Expected: new UI/workflow behavior fails before implementation.
- [ ] **Step 3: Implement the Compose card and settings.** Add a separate “Автоматически подбирать компенсацию” switch. Keep saved-period-share toggle independent. Show source/target breakdown, projected deficits, uncovered remainder and applied-period transfer history on Overview. One explicit action applies the entire proposal atomically; turning off automation only hides future proposals.
- [ ] **Step 4: Remove the old duplicated Analytics-only redistribution block**, ensure Overview actual-spend labels exclude future-dated expenses, and ensure the new effective plan amounts include ledger adjustments without modifying real expense/income rows.
- [ ] **Step 5: Run the full unit suite and available UI tests.** Expected: no duplicate transfer, no actual transaction mutation, no regression in saved shares or Overview navigation.
- [ ] **Step 6: Commit** `feat: surface and apply period budget redistribution proposals`.

### Task 5: Reconcile analytics/export, verify, and prepare Release

**Files:**
- Modify: `app/src/main/java/ru/scooblydoon/thunbudget/MainActivity.kt` (monthly category amounts and Excel export plans)
- Modify: `app/src/main/java/ru/scooblydoon/thunbudget/BudgetAllocation.kt` only if a shared legacy calculation needs compatibility wiring.
- Modify: `app/build.gradle.kts` (1.3.45 / versionCode 58)
- Modify: `.github/workflows/android.yml` only if the feature branch needs an explicit workflow addition.
- Test: existing analytics/export suites and all new redistribution suites.

- [ ] **Step 1: Write failing reconciliation tests** showing period totals and exported monthly category plan reflect base shares plus confirmed ledger adjustments, while expense history/actual chart totals remain unchanged.
- [ ] **Step 2: Run the targeted tests** and confirm the mismatch.
- [ ] **Step 3: Implement reconciliation** for selected period, reporting month and export; do not broaden unrelated analytics behavior.
- [ ] **Step 4: Independently recompute the 946 ₽ example and global 2% safety-buffer scenarios** from fixtures, then run `gradle :app:testDebugUnitTest`.
- [ ] **Step 5: Bump Release version** to 1.3.45 / 58 and push to the existing feature branch to trigger the signed Release CI workflow.
- [ ] **Step 6: Verify exact branch/head SHA, full test results, Release build, signature certificate, APK artifact/version, and update PR #15.**
- [ ] **Step 7: Self-review the entire diff** (no independent Codex subagent tool is currently exposed in this environment). Do not merge PR #15 or modify main.
