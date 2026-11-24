## Income calculation fixes (2026-11-24)
- Sum both reporting-period income entries for the selected month and show the raw total separately from the estimated full-calendar-month income.
- Estimate a full-month amount from the average daily income across the selected reporting periods, scaled to the calendar month's actual number of days.
- Change the period-income quick action to append a new income transaction without overwriting existing entries.
- Carry forward the unspent balance of completed reporting periods into the next period's available budget, without counting carryover as new income.
- Add regression tests for monthly normalization and accumulated carryover.

# Changelog

## 1.3.14
- Parse expense title independently from category in voice input (for example, “автобус 100 рублей в транспорт”).
- Add rollback for applied dynamic allocations, restoring the previous manually configured category shares.
- Add an optional persistent automatic allocation mode that recalculates category shares from actual spending in the selected Overview reporting period as expenses and income data change.
- Add a regression test for separating an expense title from its category.
- Release APK build requested after the 1.3.13 release.
## 1.3.13
- Add a choice between configured monthly income and the sum of the two reporting-period incomes for the selected month.
- Add a dynamic allocation forecast based on actual spending recorded in the currently selected Overview reporting period, with editable proposed shares and reserve remainder.
- Show category free-balance percentage and required budget increase percentage in monthly and selected-period analytics.
- Add unit tests for monthly income modes, category budget metrics, and dynamic allocation proposals.

## 1.3.12
- Fix assistant date display to use dd.MM.yyyy and convert dates to natural Russian speech.
- Fix date-to-speech matching and interval selection for average spending requests.
- Add deterministic queries for most frequent spending category and expense title, largest and smallest expenses, average spending by day/week/month/quarter/year and category.
- Add assistant spending charts by category and monthly trend.
- Add a discoverable “Что я могу сказать?” help response with command examples.
- Add tests for analytics queries, calculations, charts, and spoken date formatting.
- Expand phrase recognition for questions about the most frequent expense title.

## 1.3.11
- Fix the empty-history date window and align allocation tests with the adaptive completed-month history rules.
- Fix allocation dialog initialization order so available expense history is prepared before suggestions are calculated.
- Add a persistent collapse/expand toggle for the floating quick-action buttons (voice input, income, add expense).

## 1.3.10
- Fix recurring payments empty state: add a prominent full-width button to create the first payment.

## 1.3.9
- Calculate suggested category shares from all available completed-month expense history, up to the latest six months, excluding the current month.
- Show each category's free budget balance as a percentage on the overview screen.
- Add prioritized budget redistribution suggestions for overspent categories, using donor categories with at least 20%, 15%, then 10% remaining budget; categories with 5% or less are excluded.
- Allow applying the suggested redistribution directly to category percentages, with undo support.
- Added tests for short and six-month history windows, free-balance percentages, and redistribution priorities.

## 1.3.8
- Added six-month budget allocation suggestions based on actual spending across the previous six completed months.
- Added manual editing of suggested category percentages and automatic reserve allocation when the total is below 100%.
- Added recurring payments with monthly due dates, reminders, confirmation, and automatic expense entry.
- Added recurring-payment data to backup and restore.

## 1.3.7
- Fixed the voice confirmation date being displayed in ISO digit order (for example, 20.26.1117); dates now initialize in DD.MM.YYYY order.
- Improved offline command recovery when speech recognition drops the command verb but retains a clear amount and known category (for example, “500 руб продукты 17 ноября”).
- Added regression tests for verb-omitted expense phrases and voice date field formatting.

## 1.3.6
- Release candidate: versionCode 19, versionName 1.3.6; unit tests and debug compilation passed.
- Fixed date entry cursor jumping by storing digits only and rendering separators through a visual date mask.
- Applied the corrected date input behavior to expense entry, expense editing, reporting-period settings, history range filters, and voice confirmation.
- Replaced the combined history month selector with independent year, month, and day filters that can be combined with category, amount, text, and date-range filters.
- Removed the appearance/theme section from Settings; the main-screen theme switch remains available at the bottom.
- Fixed the expense structure ring chart being clipped by drawing the stroked arcs inside their canvas bounds.
- Improved voice command parsing to recognize natural phrasing such as “Внеси 500 рублей в транспорт за 15 ноября”.
- Added “внеси/внести” as expense commands, retained fuzzy category matching, and added spoken date inference for day + month without a year.
- If a voice command has no date, the current date is used; a day and month without a year resolve to the most recent occurrence not later than today.
- Added regression tests for the natural voice command, default current date, and year inference.

# Changelog

## 1.3.5
- Removed the T-Bank notification listener, transaction queue, and related Settings controls.
- Improved offline voice amount parsing, including decimal values and kopecks.
- Improved voice category matching to tolerate minor recognition errors and Russian word inflections.
- Added regression tests for decimal amounts, kopecks, explicit dates, fuzzy categories, and digit-only date formatting.
- Added voice date recognition for today, yesterday, the day before yesterday, numeric dates, and dates with month names.
- Standardized date entry to DD.MM.YYYY with digits-only input and automatic separators across expenses, history filters, and reporting-period settings.
- Added a month filter to operation history; month, category, text, amount, and date filters can be combined.
- Moved the light/dark theme switch to the bottom of the main screen.
- Made the expense-structure chart adapt to narrow screens.
- Added a full data reset action in Settings with a red button and explicit confirmation.
- Kept data local and preserved the existing budget layout.

# Changelog

## 1.3.1
- Release build: versionCode 14, versionName 1.3.1.
- Reorganized Settings into separate sections for income and savings, planning period, categories, Excel export, backups, T-Bank import, and appearance.
- Moved Excel report export into its own Settings section, separate from backup and restore.
- Clarified the dedicated Android notification-listener permission and added an explicit status refresh action.
- Refresh notification-listener permission state and pending bank operations when the app resumes.
- Improved notification-listener permission component matching.
- Recognize signed positive T-Bank money-transfer notifications such as “+10 ₽ · Перевод денежных средств” as income.
- Added a parser regression test for signed incoming transfers.
- Kept the main budget screen layout and local-only data handling unchanged.

# Changelog

## 1.3.0
- Added local T-Bank transaction capture from Android notifications.
- Added a review queue: detected operations require user confirmation and category selection before being added.
- Added duplicate protection for identical imported operations.
- Improved parsing of expanded notifications and decimal amounts; ambiguous notifications are ignored.
- Added parser tests for purchases, incoming transfers, decimal values, repeated notification text, and ambiguous amounts.
- Notification processing is limited to the T-Bank Android package and stays on the device.
- Existing budget screens and their layout remain unchanged.

# Changelog

## 1.2.0

### Data protection
- Retain up to six validated automatic backups in app-private storage.
- Add optional automatic JSON backups to a user-selected Android folder.
- Run external backup writes through WorkManager, verify saved bytes, retry transient errors, and retain up to 30 recent automatic files.
- Show the last successful external backup and external backup errors in Settings.
- Preserve the selected external folder across data restores.
- Validate expense IDs, income entries, categories, percentages, dates, and configured reporting-period bounds before accepting a backup.
- Restrict restored preference keys and reject invalid snapshots before changing app data.

### Excel export
- Use the reporting-period start and end dates configured in the app instead of hard-coded calendar ranges.
- Use recorded period income for the selected period and recorded monthly income for monthly reports, with the configured monthly income as fallback.
- Align recommendations with the app's configured reporting dates.
- Add formula-driven totals and automatic workbook recalculation.
- Add currency, percentage, and date formats; wrapped text; column sizing; frozen headers; filters; repeated print headers; and page setup.
- Add unit tests for period boundaries, budget calculations, monthly income, worksheet XML, styles, and print settings.

### Compatibility
- Keep local-first storage and existing navigation, main-screen layout, theme, and 2×2 widget.
- No cloud synchronization or remote financial-data storage.


## 1.3.23 (versionCode 36)
- Added a collapsible, locally remembered pending recurring payments section.


## 1.3.24 (versionCode 37)
- Added a monthly calendar view for recurring payments alongside the existing list.
- Calendar dates highlight scheduled payments and open compact payment details.
- Added editing, pause/resume, delete and month-aware payment confirmation from the details dialog.
- Preserved the selected list/calendar view between app launches.


## 1.3.25 (versionCode 38)
- Reworked the bottom quick actions into a docked row so they no longer cover dashboard cards.
- Reduced bottom navigation label wrapping on narrow screens and larger system font scales.
- Moved the theme switch above the navigation bar so it respects Android system navigation insets.
- Adjusted overview scroll padding to match the new docked layout.

- Preserved the existing dashboard calculations and visual theme.
- Compact mode is activated automatically on narrower screens, short layouts, or enlarged system text; standard-width phones keep their existing floating controls.
