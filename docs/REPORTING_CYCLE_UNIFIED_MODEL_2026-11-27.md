# Reporting-cycle model (2026-11-27)

## Scope

The primary ThunBudget reporting month is the pair of configured reporting periods, not a Gregorian calendar month. With the default settings:

- Period 1: day 9 through day 23 (inclusive).
- Period 2: day 24 through day 8 of the next month (inclusive).
- Reporting month: period 1 start through period 2 end (9th–8th).

Calendar-month charts remain a separate view and must not feed the primary reporting-month totals.

## Single source of truth

For a selected reporting month:

1. Income = earned income in period 1 + earned income in period 2.
2. Carryover is not new earned income and must not be counted twice.
3. Category plan = period 1 income × its saved period 1 share + period 2 income × its saved period 2 share.
4. Actual expense = cash expenses dated within the reporting-month range through today (or through the full end date for a completed cycle).
5. The technical `Перенос средств` row is an internal period-closing allocation, not a cash expense and not a redistribution donor.
6. Savings/reserve transfers remain cash outflows under the app's available-main-card-balance policy; returned savings are additional income recorded in their actual period.

The average daily expense is actual cash expense divided by the number of elapsed dates in the reporting cycle, inclusive.

## Dynamic redistribution

- Cover actual overspending only after comparing actuals with the current reporting-period category limits.
- Keep forecast spending as a safety check when selecting donors.
- Use reserve categories first.
- Ordinary donors must have more than 5% forecast free balance; prioritize donor tiers of at least 20%, then 15%, then 10%, then the remaining eligible band above 5%.
- Preserve the savings category from automatic donor use.
- Never use `Перенос средств` as a donor or deficit category.

## Export and validation

The Excel "Отчётный месяц" sheet uses the same configured cycle dates and cut-off as the app. The annual sheet remains a calendar-year report. Regression tests cover active/completed cycle boundaries, monthly Excel facts and plan, and redistribution donor ordering/eligibility.

No database migration is required: existing income ledger rows and saved per-period category-share snapshots remain the source data.
