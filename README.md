# ThunBudget Analytics

Offline-first personal budget tracker for Android. Russian interface; user data is stored locally on the device.

## Current features

- Configurable reporting periods and income for each period.
- Expense entry with date, description and category, plus offline-capable voice input where the device supports it.
- Category budgets, actual spending, remaining amounts and overspend indicators.
- Monthly and reporting-period analytics, spending charts and a daily-rate forecast through the end of the period.
- Operation history with search, category/date/amount filters, sorting, editing and deletion.
- XLSX export compatible with Excel, using the reporting-period dates configured in the app, recorded monthly income where available, annual category analysis, formula-driven totals, frozen headers, filters, wrapped text and print settings.
- JSON backup and restore with up to 6 verified local snapshots. An optional Android folder can receive automatic timestamped JSON backups through WorkManager; external files are verified after writing and the 30 most recent are retained. Settings shows the last successful external backup and any current backup error. Before a restore, the app keeps a local safety snapshot; Settings offers **Undo last restore** to return to the previous data.
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

Create a JSON backup before reinstalling the app or changing devices. Restoring a backup replaces the current app data, but the app first saves the previous state locally so it can be rolled back from Settings. Internal snapshots are stored in app-private storage and are removed when the app is uninstalled or its storage is cleared. For protection against that scenario, select an external folder for automatic backups or export a JSON file to a separate location.
