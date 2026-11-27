# Assistant universal access, flexible-period reports and local memory

Date: 2026-11-27
Base: `feature/flexible-periods-analytics-2026-11-26`

## Goal

Make the in-app assistant a natural-language interface to the application's existing screens and calculations. The assistant must be able to retrieve any supported information from the local ledger, build reports/charts for named flexible periods, and retain enough context for short follow-up questions.

## Non-negotiable architecture

1. All financial records stay on device. No full ledger, transaction list, bank notification text, or raw user conversation is sent to a cloud service.
2. Natural-language interpretation returns a typed command. The model/parser never performs arithmetic and never writes storage directly.
3. Existing calculators remain the single source of truth. The assistant calls the same calculation functions as the app screens.
4. Read-only queries are side-effect free. Any transaction, category, budget, period, reminder, or plan mutation is previewed and requires explicit confirmation.
5. Every answer should expose its scope: selected period dates, filters, number of transactions, and whether income is actual or prorated.
6. When scope is ambiguous, ask one short clarification instead of silently selecting an unrelated period.

## Capability catalogue

### Read-only queries
- Dashboard: current income, expenses, plan, remaining budget, daily average/limit.
- Ledger: operations by date, category, title, amount, type; largest/smallest/average; unclassified or potentially duplicated operations.
- Categories: actual, plan, remaining, execution percentage, top categories by sum and frequency.
- Standard reporting periods: full report, period income, carryover, forecast, overspend.
- Flexible periods: list periods; report by period name; actual income, carryover, prorated fallback and total budget income; actual, planned, projected and recurring expense totals; category breakdown; comparison with adjacent period; category and time-series charts.
- Recurring payments: upcoming, overdue, completed, expected total inside selected period, changes in amount/frequency.
- Savings: goal progress and contribution pace, when the relevant entities are present.
- Navigation: open an existing screen or report section without modifying data.

### Mutations (confirmation required)
- Add/update/delete income or expense.
- Add/rename/edit/delete category or percentage allocation.
- Change period definitions or budgets.
- Add/update/delete planned recurring payment or savings goal.
- Bulk changes must show exact record count and a preview; destructive actions require a second explicit confirmation.

## Flexible-period semantics

A flexible period is identified by its saved name and stable local ID, but the parser/model may only suggest the name; it must never provide a database ID. Android resolves the name to exactly one saved period.

For a selected period, use `buildFlexiblePeriodReport(...)` / `FlexiblePeriodAnalyticsCalculator` as the canonical financial calculation path. Do not independently re-create period income, carryover, forecast, recurring-payment or category-allocation formulas inside the assistant.

The report must distinguish:
- earned income recorded in the period;
- carryover from prior period;
- prorated monthly-income fallback (only when no dated income exists);
- total budget income used by the calculator;
- planned amount by category;
- actual expenses up to the current date;
- future-dated entered expenses;
- modelled variable forecast;
- expected recurring payments not yet represented by future-dated expenses;
- projected total and low/high range.

A flexible-period answer must say when the amount is based on prorated fallback rather than actual dated income. Transfers/carryover are not expenses. Avoid double-counting future-entered transactions and recurring forecast items.

## Recognition knowledge base

Use a versioned, local, inspectable dictionary rather than embedding hundreds of words in conditional branches. It should contain:

- intent phrases and synonyms (report/show/calculate/compare/find/chart/open/add/edit/delete);
- time phrases (today, yesterday, this week, last 30 days, last month, year-to-date, explicit date ranges);
- flexible-period aliases, resolved against current saved period names;
- category aliases and morphological variants (ё/е, case endings, singular/plural, common colloquial terms);
- merchant aliases and brand variants, maintained separately from categories;
- metric vocabulary (income, earned income, carryover, plan, actual, forecast, projected total, daily average);
- chart dimensions (by category, by day, by week, by month, actual vs plan vs forecast);
- navigation labels matching existing app sections.

Resolution priority:
1. Exact saved category/period/merchant name.
2. User-confirmed local alias.
3. Built-in curated Russian alias dictionary.
4. Conservative morphology/normalization.
5. Fuzzy match only to produce suggestions; never auto-select on a low-confidence match.

MCC may be an optional imported field in the future, but it is not a required or sufficient recognition database: merchant category codes describe a merchant's primary business, not the exact items bought. Prefer transaction description, normalized merchant, user category, and user correction history. Do not change existing transaction schema solely to introduce MCC before an import source actually supplies it.

## Local assistant memory

Persist only compact query context in a versioned SharedPreferences key:
- last selected period ID/name/date range;
- last selected category;
- last intent/topic and update timestamp;
- bounded turn counter.

Do not persist raw prompts by default, complete assistant answers, balances, transaction rows, or copied bank notification contents. Resolve a remembered period ID against the currently existing periods on every use; if the period was deleted, discard it and ask/choose a valid current scope. Provide a visible “Clear assistant context” action in settings or chat.

Follow-up examples:
- “Покажи отчёт за Отпуск” → remembers selected period.
- “А теперь только доход” → reuses the last period and returns actual/carryover/fallback income.
- “Построй график по категориям” → reuses the same period.
- “А транспорт?” → reuses the same period and narrows to the remembered category only when the phrase is genuinely elliptical.

Memory is not authority: explicit dates, period names, or category terms in the current message always override stored context.

## Output contract

Every financial answer should contain:
- a one-line summary;
- exact period start/end and whether it is standard or flexible;
- figures with labels, not bare numbers;
- actual vs plan vs forecast clearly separated;
- count of source operations and access to the filtered ledger;
- assumptions and missing-data warning where applicable.

Charts must be built from deterministic result rows and use the same selected filters as the text answer. At minimum support category bars, daily timeline, monthly trend, and plan-vs-actual-vs-forecast for flexible periods.

## Test matrix

- Named period with exact name, case differences, ё/е, and similar names.
- No period name: active period, no active period, multiple periods, no saved periods.
- Follow-up after named-period report; explicit new period overrides memory.
- Deleted remembered period; corrupt/old memory JSON; clear memory.
- Income ledger present; carryover present; no dated income and prorated fallback; empty period.
- Period includes future-dated expenses and active recurring payments; ensure no double count.
- Transfers excluded from expense totals.
- Category aliases and ambiguous aliases; exact category wins.
- Chart and text answer use identical date/category filters.
- Mutation commands cannot execute without preview and explicit confirmation.

## Implementation sequence

1. Typed intents and parser support for flexible-period names and full-report/income/chart queries.
2. Integrate assistant reads with the canonical flexible-period report builder.
3. Add small local structured context memory and follow-up resolution.
4. Expand local recognition dictionaries and correction flow.
5. Add missing report dimensions/navigation and full ledger drill-down.
6. Run unit tests, then build Release APK on the dedicated feature branch. Do not merge into `main` until the build and regression checklist pass.
