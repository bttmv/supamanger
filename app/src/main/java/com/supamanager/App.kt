package com.supamanager
import android.app.Application
import android.content.Context
import androidx.work.*
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

class KeepAliveWorker(c: Context, w: WorkerParameters) : CoroutineWorker(c, w) {
    override suspend fun doWork(): Result {
        Store.load(applicationContext).forEach {
            val r = runCatching { Api.keepAlive(it) }.getOrElse { e -> "فشل: ${e.message}" }
            Store.log(applicationContext, it.name, "$r — ${DateFormat.getDateTimeInstance().format(Date())}")
        }
        return Result.success()
    }
}

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val req = PeriodicWorkRequestBuilder<KeepAliveWorker>(12, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("keepalive", ExistingPeriodicWorkPolicy.KEEP, req)
    }
}
