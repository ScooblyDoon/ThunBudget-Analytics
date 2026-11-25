package ru.scooblydoon.thunbudget

import android.content.Context
import io.appwrite.Client

/**
 * Appwrite connection bootstrap.
 *
 * This initializes only the public client configuration. It does not perform
 * authentication, create remote resources, or read/write any budget data.
 */
internal object AppwriteBackend {
    private const val ENDPOINT = "https://fra.cloud.appwrite.io/v1"
    private const val PROJECT_ID = "6b06891100105876e2ba"

    @Volatile
    private var initialized = false

    lateinit var client: Client
        private set

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            client = Client(context.applicationContext)
                .setEndpoint(ENDPOINT)
                .setProject(PROJECT_ID)
            initialized = true
        }
    }
}
