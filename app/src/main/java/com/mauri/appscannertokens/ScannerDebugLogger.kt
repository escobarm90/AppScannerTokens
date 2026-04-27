package com.mauri.appscannertokens

import android.util.Log

object ScannerDebugLogger {
    private const val TAG = "ScannerDebug"

    fun d(message: String) {
        Log.d(TAG, message)
    }

    fun e(message: String, throwable: Throwable? = null) {
        if (throwable == null) {
            Log.e(TAG, message)
        } else {
            Log.e(TAG, message, throwable)
        }
    }
}
