package knf.kuma.download

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class DownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val did = intent.getIntExtra("did", 0)
        when (intent.getIntExtra("action", -1)) {
            DownloadManager.ACTION_PAUSE -> DownloadManagerCentral.pause(did)
            DownloadManager.ACTION_RESUME -> DownloadManagerCentral.resume(did)
            DownloadManager.ACTION_CANCEL -> DownloadManagerCentral.cancel(
                intent.getStringExtra("eid")
                    ?: "")
        }
    }
}
