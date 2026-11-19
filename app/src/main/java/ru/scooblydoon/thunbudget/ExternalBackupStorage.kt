package ru.scooblydoon.thunbudget

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Writes immutable, timestamped JSON snapshots to a user-selected SAF folder.
 * A failed write is removed so incomplete files are never presented as backups.
 */
internal object ExternalBackupStorage {
    private const val FILE_PREFIX = "ThunBudget-auto-"
    private const val KEEP_FILES = 30

    fun save(context: Context, treeUri: Uri, json: String) {
        val resolver = context.contentResolver
        val treeId = DocumentsContract.getTreeDocumentId(treeUri)
        val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeId)
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
            .withZone(ZoneId.systemDefault()).format(Instant.now())
        val name = "$FILE_PREFIX$stamp.json"
        val created = DocumentsContract.createDocument(
            resolver, parent, "application/json", name
        ) ?: error("Папка резервного копирования недоступна.")

        try {
            val expectedBytes = json.toByteArray(Charsets.UTF_8)
            resolver.openOutputStream(created, "w")?.use { stream ->
                stream.write(expectedBytes)
                stream.flush()
            } ?: error("Не удалось открыть файл резервной копии.")
            val actualBytes = resolver.openInputStream(created)?.use { it.readBytes() }
                ?: error("Не удалось проверить сохранённую резервную копию.")
            require(actualBytes.contentEquals(expectedBytes)) {
                "Проверка сохранённой резервной копии не пройдена."
            }
        } catch (error: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, created) }
            throw error
        }

        pruneOldCopies(context, treeUri, treeId)
    }

    private fun pruneOldCopies(context: Context, treeUri: Uri, treeId: String) {
        val resolver = context.contentResolver
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeId)
        val rows = mutableListOf<Triple<Long, String, Uri>>()
        resolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
            ),
            null, null, null
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val modifiedColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameColumn) ?: continue
                if (!name.startsWith(FILE_PREFIX) || !name.endsWith(".json")) continue
                val id = cursor.getString(idColumn) ?: continue
                val modified = if (modifiedColumn >= 0 && !cursor.isNull(modifiedColumn)) cursor.getLong(modifiedColumn) else 0L
                rows += Triple(modified, name, DocumentsContract.buildDocumentUriUsingTree(treeUri, id))
            }
        } ?: return

        rows.sortedWith(compareByDescending<Triple<Long, String, Uri>> { it.first }.thenByDescending { it.second })
            .drop(KEEP_FILES).forEach { (_, _, uri) -> runCatching { DocumentsContract.deleteDocument(resolver, uri) } }
    }
}

internal class ExternalBackupWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val preferences = applicationContext.getSharedPreferences("thunbudget", Context.MODE_PRIVATE)
        val rawUri = preferences.getString(ExternalBackupSettings.URI_KEY, null) ?: return Result.success()
        return try {
            val snapshot = BudgetStore(applicationContext).validatedBackupSnapshot()
                ?: return Result.retry()
            ExternalBackupStorage.save(applicationContext, Uri.parse(rawUri), snapshot)
            preferences.edit().putString(ExternalBackupSettings.LAST_SUCCESS_KEY, Instant.now().toString()).apply()
            Result.success()
        } catch (_: SecurityException) {
            Result.failure()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

internal object ExternalBackupSettings {
    const val URI_KEY = "external_backup_tree_uri"
    const val LAST_SUCCESS_KEY = "external_backup_last_success"
    private const val WORK_NAME = "thunbudget_external_backup"

    fun schedule(context: Context) {
        val request = OneTimeWorkRequestBuilder<ExternalBackupWorker>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME, ExistingWorkPolicy.REPLACE, request
        )
    }
}
