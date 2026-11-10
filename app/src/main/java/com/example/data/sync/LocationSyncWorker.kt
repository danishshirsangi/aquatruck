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
import kotlinx.coroutines.CancellationException

class LocationSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val dao = AppDatabase.getDatabase(applicationContext).fleetDao()
        val syncManager = FirestoreSyncManager(applicationContext)

        return try {
            var completedBatches = 0

            // Bound each worker execution so a long offline backlog cannot monopolize
            // the device. Remaining rows are retried with WorkManager backoff.
            while (completedBatches < MAX_BATCHES_PER_RUN) {
                var processedAny = false

                val pendingLocations = dao.getPendingLocationPoints()
                if (pendingLocations.isNotEmpty()) {
                    val ids = pendingLocations.map { it.id }
                    if (!syncManager.syncLocationBatch(pendingLocations)) {
                        dao.incrementLocationRetryCount(ids)
                        return Result.retry()
                    }
                    dao.markLocationPointsSynced(ids)
                    processedAny = true
                }

                val pendingStops = dao.getPendingStopRecords()
                if (pendingStops.isNotEmpty()) {
                    val ids = pendingStops.map { it.id }
                    if (!syncManager.syncStopBatch(pendingStops)) {
                        dao.incrementStopRetryCount(ids)
                        return Result.retry()
                    }
                    dao.markStopRecordsSynced(ids)
                    processedAny = true
                }

                val pendingVoiceNotes = dao.getPendingVoiceNotes()
                if (pendingVoiceNotes.isNotEmpty()) {
                    for (note in pendingVoiceNotes) {
                        if (!syncManager.syncVoiceNote(note)) {
                            dao.incrementVoiceNoteRetryCount(listOf(note.id))
                            return Result.retry()
                        }
                        dao.markVoiceNotesSynced(listOf(note.id))
                    }
                    processedAny = true
                }

                if (!processedAny) break
                completedBatches++
            }

            // Never delete pending records. Synced location history is retained for 30 days.
            val retentionCutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30)
            dao.deleteLocationPointsBefore(retentionCutoff)

            val hasPendingRecords =
                dao.getPendingLocationCount() > 0 || dao.getPendingStopCount() > 0 || dao.getPendingVoiceNoteCount() > 0

            if (hasPendingRecords) {
                Log.i("LocationSyncWorker", "Sync batch limit reached; remaining records will retry.")
                Result.retry()
            } else {
                Result.success()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("LocationSyncWorker", "Location sync failed: ${e.message}", e)
            Result.retry()
        }
    }

    companion object {
        private const val MAX_BATCHES_PER_RUN = 5
    }
}

object LocationSyncScheduler {
    private const val UNIQUE_WORK_NAME = "aquatruck-location-history-sync"

    fun enqueue(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<LocationSyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }
}
