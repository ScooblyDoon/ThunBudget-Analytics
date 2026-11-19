# Changelog

## 1.3.1
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
