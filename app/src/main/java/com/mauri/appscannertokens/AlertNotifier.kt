package com.mauri.appscannertokens

import android.content.Context
import android.media.RingtoneManager

object AlertNotifier {
    fun playNotification(context: Context) {
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            RingtoneManager.getRingtone(context.applicationContext, uri).play()
        }
    }
}
