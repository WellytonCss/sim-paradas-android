package br.com.amaggi.simparadas

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** Envia a fila de lançamentos em segundo plano assim que houver internet (mesmo com o app fechado). */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Repo.init(applicationContext)
        return if (Repo.syncNow()) Result.success() else Result.retry()
    }
}
