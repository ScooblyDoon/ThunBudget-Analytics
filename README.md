# ThunBudget Analytics

Offline-first personal budget tracker for Android. Russian interface; user data is stored locally on the device.

## Current features

- Configurable reporting periods and income for each period.
- Expense entry with date, description and category, plus offline-capable voice input where the device supports it.
- Category budgets, actual spending, remaining amounts and overspend indicators.
- Monthly and reporting-period analytics, spending charts and a daily-rate forecast through the end of the period.
- Operation history with search, category/date/amount filters, sorting, editing and deletion.
- XLSX export compatible with Excel.
- JSON backup and restore. Before a restore, the app keeps a local safety snapshot; Settings offers **Undo last restore** to return to the previous data.
- Local persistence using Android SharedPreferences; no cloud account or internet connection is required for normal use.

## Build APK

Every push to `main` and manual workflow dispatch builds an APK.
Open **Actions → Android APK**, select the latest run, and download the corresponding artifact. When release signing secrets are configured correctly, the workflow creates `ThunBudget-Analytics-release`; otherwise it creates a temporary debug APK that cannot update an installation signed with a different key.

## Build locally

Requires JDK 17, Android SDK 35 and Gradle 8.9:

```bash
gradle :app:assembleDebug
```

## Data safety

Create a JSON backup before reinstalling the app or changing devices. Restoring a backup replaces the current app data, but the app first saves the previous state locally so it can be rolled back from Settings. Uninstalling the app or clearing its storage still removes local data; keep exported backup files in a separate location.
