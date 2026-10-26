package knf.kuma.download

import android.app.job.JobScheduler
import android.content.Context
import android.os.Build
import knf.kuma.App
import knf.kuma.pojos.DownloadObject

object DownloadManagerCentral {
    private val isSchedulerEnabled = try {
        App.context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    } catch (_: Exception) {
        false
    }

    fun start(downloadObject: DownloadObject): Boolean {
        return if (isSchedulerEnabled) {
            DownloadManagerJob.start(downloadObject)
        } else {
            DownloadManager.start(downloadObject)
        }
    }

    fun cancel(eid: String) {
        if (isSchedulerEnabled) {
            DownloadManagerJob.cancel(eid)
        } else {
            DownloadManager.cancel(eid)
        }
    }

    fun cancelAll() {
        if (isSchedulerEnabled) {
            DownloadManagerJob.cancelAll()
        } else {
            DownloadManager.cancelAll()
        }
    }

    fun pause(downloadObject: DownloadObject) {
        if (isSchedulerEnabled) {
            DownloadManagerJob.pause(downloadObject)
        } else {
            DownloadManager.pause(downloadObject)
        }
    }

    fun pauseAll() {
        if (isSchedulerEnabled) {
            DownloadManagerJob.pauseAll()
        } else {
            DownloadManager.pauseAll()
        }
    }

    fun pause(did: Int) {
        if (isSchedulerEnabled) {
            DownloadManagerJob.pause(did)
        } else {
            DownloadManager.pause(did)
        }
    }

    fun resume(downloadObject: DownloadObject) {
        if (isSchedulerEnabled) {
            DownloadManagerJob.resume(downloadObject)
        } else {
            DownloadManager.resume(downloadObject)
        }
    }

    fun resume(did: Int) {
        if (isSchedulerEnabled) {
            DownloadManagerJob.resume(did)
        } else {
            DownloadManager.resume(did)
        }
    }

    fun setParallelDownloads(newValue: String?) {
        if (isSchedulerEnabled) {
            DownloadManagerJob.setParallelDownloads(newValue)
        } else {
            DownloadManager.setParallelDownloads(newValue)
        }
    }
}