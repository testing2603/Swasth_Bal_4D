package gov.up.swasthbal4d.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import gov.up.swasthbal4d.R
import gov.up.swasthbal4d.SwasthBalApp
import gov.up.swasthbal4d.data.Repository
import gov.up.swasthbal4d.data.net.AppJson
import gov.up.swasthbal4d.data.net.FullVisitIn
import gov.up.swasthbal4d.data.net.SyncIn
import gov.up.swasthbal4d.data.net.WorkerResult
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException
import java.io.File
import java.util.concurrent.TimeUnit

object SyncScheduler {
    private val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun syncNow(context: Context) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(connected)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("sync-visits", ExistingWorkPolicy.KEEP, req)
    }

    fun uploadMedia(context: Context) {
        val req = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(connected)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("upload-media", ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }

    const val CHANNEL = "results"

    fun createChannel(context: Context) {
        val ch = NotificationChannel(CHANNEL, "अंतिम नतीजे", NotificationManager.IMPORTANCE_DEFAULT)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }
}

/** Encrypted outbox -> server. Idempotent: the visit UUID is the key, so retries never duplicate. */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val repo = (applicationContext as SwasthBalApp).repo
        val pending = repo.db.outbox().pending()
        if (pending.isEmpty()) return Result.success()
        val payload = pending.map { AppJson.decodeFromString(FullVisitIn.serializer(), it.payloadJson) }
        val out = try {
            repo.client.api.sync(SyncIn(payload))
        } catch (e: Exception) {
            pending.forEach { repo.db.outbox().put(it.copy(attempts = it.attempts + 1)) }
            return if (runAttemptCount < 10) Result.retry() else Result.failure()
        }
        for (r in out.results) {
            val row = pending.firstOrNull { it.id == r.clientId } ?: continue
            when (r.status) {
                "synced", "already_synced", "refused", "draft_saved" -> {
                    repo.db.outbox().put(row.copy(status = "synced", finalColor = r.final,
                        resultJson = AppJson.encodeToString(WorkerResult.serializer(), r)))
                    if (r.final != null) notifyFinal(row.childName, r)
                }
                else -> repo.db.outbox().put(row.copy(status = "error", attempts = row.attempts + 1))
            }
        }
        repo.db.outbox().prune(System.currentTimeMillis() - 30L * 24 * 3600 * 1000)
        SyncScheduler.uploadMedia(applicationContext)
        return Result.success()
    }

    private fun notifyFinal(childName: String, r: WorkerResult) {
        if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) return
        val changed = if (r.changedFromProvisional) " (बदला)" else ""
        val n = NotificationCompat.Builder(applicationContext, SyncScheduler.CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("अंतिम नतीजा$changed · $childName")
            .setContentText(Repository.labelHi(r.final))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(applicationContext).notify(childName.hashCode(), n)
    }
}

/** Upload order: answers first (outbox), then photos -> audio -> video. Raw media is deleted after upload. */
class UploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val repo = (applicationContext as SwasthBalApp).repo
        var retry = false
        for (m in repo.db.media().pending()) {
            val f = File(m.path)
            if (!f.exists()) { repo.db.media().mark(m.id, "missing"); continue }
            val mime = when (m.kind) { "photo" -> "image/webp"; "video" -> "video/mp4"; else -> "audio/mp4" }
            val text = "text/plain".toMediaType()
            try {
                repo.client.api.uploadMedia(
                    m.visitId, m.domain.toRequestBody(text), m.kind.toRequestBody(text), m.tag.toRequestBody(text),
                    MultipartBody.Part.createFormData("file", f.name, f.asRequestBody(mime.toMediaType())),
                )
                repo.db.media().mark(m.id, "done")
                f.delete()
            } catch (e: HttpException) {
                // 404 / 403: visit or its consent not on the server yet - outbox sync comes first
                if (e.code() == 404 || e.code() == 403) retry = true
                else repo.db.media().mark(m.id, "rejected_${e.code()}")
            } catch (e: Exception) {
                retry = true
            }
        }
        return if (retry && runAttemptCount < 20) Result.retry() else Result.success()
    }
}
