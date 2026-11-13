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
            var anyFailure = false

            // Bound each worker execution so a long offline backlog cannot monopolize
            // the device. Remaining rows are retried with WorkManager backoff.
            // Each category is isolated: a failing GPS batch must never block stops or voice notes.
            while (completedBatches < MAX_BATCHES_PER_RUN) {
                var processedAny = false

                val pendingLocations = dao.getPendingLocationPoints()
                if (pendingLocations.isNotEmpty()) {
                    val ids = pendingLocations.map { it.id }
                    Log.i(
                        "LocationSyncWorker",
                        "Uploading ${pendingLocations.size} GPS points for trucks=${pendingLocations.map { it.truckId }.distinct()}"
                    )
                    if (syncManager.syncLocationBatch(pendingLocations)) {
                        dao.markLocationPointsSynced(ids)
                        processedAny = true
                    } else {
                        // Isolate the poison point(s) instead of retrying the same 200 forever.
                        val (ok, denied) = syncManager.syncLocationPointsIndividually(pendingLocations)
                        if (ok.isNotEmpty()) {
                            dao.markLocationPointsSynced(ok.toList())
                            processedAny = true
                        }
                        val deadIds = pendingLocations
                            .filter { it.id in denied && it.retryCount >= MAX_DENIED_RETRIES }
                            .map { it.id }
                        if (deadIds.isNotEmpty()) {
                            Log.e("LocationSyncWorker", "Giving up on ${deadIds.size} GPS points repeatedly denied by Firestore rules (no active claim on their truck?).")
                            dao.markLocationPointsSynced(deadIds)
                            processedAny = true
                        }
                        val retryIds = pendingLocations
                            .filter { it.id !in ok && it.id !in deadIds }
                            .map { it.id }
                        if (retryIds.isNotEmpty()) {
                            dao.incrementLocationRetryCount(retryIds)
                            anyFailure = true
                        }
                    }
                }

                val pendingStops = dao.getPendingStopRecords()
                if (pendingStops.isNotEmpty()) {
                    val ids = pendingStops.map { it.id }
                    if (syncManager.syncStopBatch(pendingStops)) {
                        dao.markStopRecordsSynced(ids)
                        processedAny = true
                    } else {
                        dao.incrementStopRetryCount(ids)
                        anyFailure = true
                    }
                }

                val pendingVoiceNotes = dao.getPendingVoiceNotes()
                for (note in pendingVoiceNotes) {
                    if (syncManager.syncVoiceNote(note)) {
                        dao.markVoiceNotesSynced(listOf(note.id))
                        processedAny = true
                    } else {
                        dao.incrementVoiceNoteRetryCount(listOf(note.id))
                        anyFailure = true
                    }
                }

                if (!processedAny) break
                completedBatches++
            }

            // Never delete pending records. Synced location history is retained for 30 days.
            val retentionCutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30)
            dao.deleteLocationPointsBefore(retentionCutoff)

            val hasPendingRecords =
                dao.getPendingLocationCount() > 0 || dao.getPendingStopCount() > 0 || dao.getPendingVoiceNoteCount() > 0

            if (anyFailure || hasPendingRecords) {
                Log.i("LocationSyncWorker", "Records still pending (failure=$anyFailure); will retry.")
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
        private const val MAX_DENIED_RETRIES = 8
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
            // GPS callbacks can arrive while an upload is running. REPLACE cancels
            // the in-flight Firestore batch on every new sample; KEEP lets that
            // batch finish. The worker drains pending rows in bounded batches,
            // and later callbacks enqueue another run after it completes.
            ExistingWorkPolicy.KEEP,
            request
        )
    }
}
