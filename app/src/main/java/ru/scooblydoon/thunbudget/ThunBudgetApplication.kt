package ru.scooblydoon.thunbudget

import android.app.Application

class ThunBudgetApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppwriteBackend.initialize(this)
    }
}
