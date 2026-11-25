# Appwrite Android bootstrap

- Appwrite endpoint: https://fra.cloud.appwrite.io/v1
- Appwrite project ID: 6b06891100105876e2ba
- Android applicationId: ru.scooblydoon.thunbudget.analytics
- Android SDK: io.appwrite:sdk-for-android:31.3.0

The Android SDK client is initialized at application startup. This bootstrap does not
authenticate users, create databases or buckets, or upload/download any budget data.
Local SharedPreferences and backup behavior remain unchanged.

Do not add an Appwrite API key to the Android app. Client applications must use
Appwrite account sessions and resource permissions; server-side administrative
operations belong in trusted server functions.
