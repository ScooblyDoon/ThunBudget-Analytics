# Automatic Reporting-Period Budget Redistribution

**Status:** Approved implementation direction for ThunBudget Analytics 1.3.45.

## Intent

Automatically identify categories that are forecast to exceed their current reporting-period budget and prepare a transparent proposal to cover those deficits using genuinely available funds in other categories. The user confirms the complete proposal once. Existing expenses and income entries remain immutable.

## User-visible behavior

- Keep **“Использовать сохранённые доли периодов”** responsible only for selecting the saved percentage plan for each reporting period.
- Add an independent **“Автоматически подбирать компенсацию”** setting. It enables/disables proposal generation; it does not apply or reverse transfers.
- When enabled, show the current-period proposal on **Обзор**, beside the category budgets, rather than burying it in Аналитика.
- Show each recipient's actual spend, expected end-of-period spend, planned amount and deficit. Show every donor, its forecast-safe available amount and the proposed contribution. Show any uncovered amount.
- One explicit **“Применить распределение”** action applies all proposed transfers atomically. Repeated taps or recompositions must not duplicate an applied proposal.
- Applied transfers remain in the selected reporting period when the automation setting is switched off. They can be undone through the existing Undo mechanism.
- Applied transfer history is visible for the selected reporting period.
- Do not create, edit or delete expense/income transactions to represent budget-only transfers.

## Financial definitions

For category *i* in reporting period *p*:

- **Base budget** = saved category share for that exact reporting period × the same effective period income already used by the Overview (period income plus the existing carryover calculation). Preserve the current distinction between a defined zero/negative period balance and a missing period income; never substitute monthly income when period income is defined.
- **Applied adjustment** = incoming confirmed budget transfers minus outgoing confirmed budget transfers for that category and period.
- **Current planned budget** = base budget + applied adjustment.
- **Actual-to-date** = expense transactions from the period start through min(today, period end). Future-dated expenses are not actuals.
- **Known future expenses** = explicitly entered expense transactions dated after today and no later than period end.
- **Unpaid recurring commitments** = active recurring payments due inside the selected period, not completed, and not already represented by a matching transaction. Match one-to-one by normalized category, due date and amount so a scheduled payment is counted once.
- **Expected spend** = actual-to-date + known future expenses + unpaid recurring commitments + central variable-expense forecast for the remaining dates.
- **Conservative donor spend** = actual-to-date + known future expenses + unpaid recurring commitments + upper variable-expense forecast for the remaining dates.
- **Recipient deficit** = max(0, expected spend − current planned budget).
- **Donor free balance** = max(0, current planned budget − conservative donor spend).

The forecasting engine's future-only forecast fields are used; actual-to-date is added exactly once. Future-dated expenses are also added exactly once. Do not count transactions that already cover recurring payments again as unpaid commitments.

## Donor protection and priority

1. Explicitly protected categories are never donors.
2. “Накопления” is always protected.
3. The reserve category (“Резервные средства” / “Непредвиденные расходы”) is considered first, but only after subtracting its own actuals, known future expenses, recurring commitments and conservative forecast.
4. Ordinary donor categories require usable history and more than 5% conservative free balance. Categories with no meaningful spending history are not assumed to be free.
5. Ordinary donors are ordered by conservative free percentage: at least 20%, at least 15%, at least 10%, then more than 5%.
6. Preserve one global safety buffer equal to 2% of effective period income across all ordinary donors (not 2% separately from every category). The reserve is considered before ordinary donors and does not consume that ordinary-donor buffer.
7. Donor amounts are limited to the donor's forecast-safe free balance. Recipient deficits are processed from largest to smallest. Money moved from all donors may partially cover a recipient; the remaining uncovered amount must be shown.

Existing categories whose names clearly represent housing/rent, utilities, loans/credit, insurance, taxes or savings default to protected when no protection field exists in an older backup. The user can explicitly configure ordinary categories as protected in category settings. Explicit stored false/true overrides the name-based default. “Накопления” remains protected regardless of the override.

## Persistence and consistency

- Store confirmed budget transfers in a separate versioned local ledger, keyed by exact period dates. A row records period, source category, destination category, amount, proposal fingerprint and timestamp.
- The ledger is included in local automatic backups, manual JSON backup/restore and Undo snapshots.
- Ledger writes are atomic. Validate category names, period dates, positive finite amounts, fingerprints and duplicate rows before saving.
- The same proposal fingerprint cannot be applied twice. Fingerprints are deterministic from the exact selected period, current planned category amounts, spending/commitment/forecast inputs and protection state.
- Switching automated recommendations off never deletes ledger rows. Editing/deleting transactions or changing income does not rewrite old expense records. Current-period proposals are recalculated from the latest actual inputs and existing applied adjustments.
- Overview category budget/percent, relevant analytics figures and exported period/month plan amounts must reconcile with the same base plan + ledger adjustments. Expense charts and transaction history must remain actual transaction data and must not include budget-only transfers.

## Non-goals

- No bank sync, cloud service, account creation, network dependency or remote analytics.
- No changes to recorded expenses/income or to reporting-period definitions/carryover formulas.
- No silent automatic application of transfers.
- No changes to approved themes, dialogs or navigation beyond the minimum space needed to present the proposal and donor-protection setting.

## Approaches considered

1. **Represent budget moves as synthetic expense transactions — rejected.** This would pollute actual spending, category charts, history and Excel exports and could be confused with real cash transfers.
2. **Rewrite category percentages when the switch is toggled — rejected.** This couples saved period shares to automation, makes repeated toggles destructive and can restore an outdated plan.
3. **Separate period-scoped budget-transfer ledger with preview and confirmation — selected.** It changes only category budget availability, leaves real transactions immutable, supports repeat-safe application, Undo, backup/restore and reconciliation across views.

## Acceptance criteria

- The screenshot scenario (Transport planned 2,582 ₽, actual 3,528 ₽, deficit 946 ₽) proposes at least one valid donor when forecast-safe money exists; it must not use protected housing/savings categories.
- A donor with upcoming rent/utilities/credit/insurance/tax obligations retains those amounts.
- A future expense that matches a recurring commitment is counted once.
- A category with low/no usable history is not silently treated as a free donor.
- One global 2% safety buffer is preserved.
- Partial coverage and uncovered remainder are visible.
- Applying a proposal updates category budget availability in Overview; actual expenses/income and their totals do not change.
- Reopening the app or toggling the automation setting cannot duplicate or reverse an already-confirmed transfer.
- Undo restores the previous period ledger and category protection settings.
- Backup export/restore preserves the new settings and ledger.
- Unit tests, Release build and established signing-certificate verification pass. Pixel 8 manual acceptance remains required before production merge.
