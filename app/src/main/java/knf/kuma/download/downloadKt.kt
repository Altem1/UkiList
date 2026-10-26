package knf.kuma.download

import android.app.Notification
import android.app.Service
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.core.content.ContextCompat
import knf.kuma.ads.AdsUtils
import knf.kuma.commons.noCrash
import org.jetbrains.anko.activityManager
import java.util.Locale

val isDeviceSamsung: Boolean get() = Build.MANUFACTURER.lowercase(Locale.getDefault()) == "samsung"

fun Context.service(intent: Intent) {
    noCrash {
        if (isDeviceSamsung && AdsUtils.remoteConfigs.getBoolean("samsung_disable_foreground"))
            startService(intent)
        else
            ContextCompat.startForegroundService(this, intent)
    }
}

fun Context.UIDT(klass: Class<*>) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        val network = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val info = JobInfo.Builder(23498, ComponentName(this, klass))
            .setUserInitiated(true)
            .setRequiredNetwork(network)
            .build()
        (getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler).schedule(info)
    }
}

fun Service.foreground(id: Int, notification: Notification, isDataSync: Boolean = true) {
    noCrash {
        if (isDeviceSamsung && AdsUtils.remoteConfigs.getBoolean("samsung_disable_foreground")) return@noCrash
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(id, notification, if (isDataSync) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(id, notification)
        }
    }
}

fun Context.isServiceRunning(serviceClass: Class<*>): Boolean{
    activityManager.getRunningServices(Int.MAX_VALUE).forEach {
        if (it.service.className == serviceClass.name)
            return true
    }
    return false
}