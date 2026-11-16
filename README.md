# ThunBudget Analytics

Offline-first personal budget tracker for Android. Russian interface; data is stored locally on the device.

## Current MVP
- Monthly income and percentage-based budget categories.
- Configurable planning period (default 14 days).
- Expense entry with date, description and category.
- Actual spending, remaining amount and category overspend indicators.
- Monthly expense breakdown.
- CSV export compatible with Excel.
- Local persistence using Android SharedPreferences.

## Build APK
Every push to `main` and manual workflow dispatch builds a debug APK.
Open **Actions → Android APK**, select the latest successful run, and download the `ThunBudget-Analytics-debug` artifact. Extract the APK from the downloaded ZIP and install it on Android.

## Build locally
Requires JDK 17, Android SDK 35 and Gradle 8.9:
```bash
gradle :app:assembleDebug
```

## Notes
This is an initial MVP. Data is kept on the device; uninstalling the app or clearing its data removes locally stored records. Cloud sync and scheduled notifications are not included yet.
