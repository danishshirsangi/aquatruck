package com.example.data.sync

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.data.db.AppDatabase
import com.example.data.firebase.FirestoreSyncManager
import java.util.concurrent.TimeUnit

class LocationSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val dao = AppDatabase.getDatabase(applicationContext).fleetDao()
        val syncManager = FirestoreSyncManager(applicationContext)

        return try {
            while (true) {
                val pending = dao.getPendingLocationPoints()
                if (pending.isEmpty()) break

                val uploaded = syncManager.syncLocationBatch(pending)
                if (!uploaded) {
                    dao.incrementLocationRetryCount(pending.map { it.id })
                    return Result.retry()
                }
                dao.markLocationPointsSynced(pending.map { it.id })
            }

            // Never delete unsynced points. Retain synced local history for 30 days.
            val retentionCutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30)
            dao.deleteSyncedLocationPointsBefore(retentionCutoff)

            if (dao.getPendingLocationCount() > 0) Result.retry() else Result.success()
        } catch (e: Exception) {
            Log.w("LocationSyncWorker", "Location sync failed: ${e.message}", e)
            Result.retry()
        }
    }
}

object LocationSyncScheduler {
    private const val UNIQUE_WORK_NAME = "aquatruck-location-history-sync"

    fun enqueue(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<LocationSyncWorker>()
            .setInitialDelay(2, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }
}
