package com.example.esp32timesync

import android.companion.CompanionDeviceService
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 登録した基板が近くに現れたら、Android が自動でこのサービスを起こす。 */
class TimeSyncService : CompanionDeviceService() {

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onDeviceAppeared(address: String) {
        AppLog.add(applicationContext, "基板が近くに来ました。時刻を送ります")
        GattTimeWriter(applicationContext) { ok, msg ->
            AppLog.add(applicationContext, (if (ok) "成功: " else "失敗: ") + msg)
        }.send(address)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onDeviceDisappeared(address: String) {
        AppLog.add(applicationContext, "基板が遠ざかりました")
    }
}

object Store {
    private fun prefs(c: Context) =
        c.getSharedPreferences("esp32timesync", Context.MODE_PRIVATE)

    fun address(c: Context): String? = prefs(c).getString("address", null)

    fun setAddress(c: Context, address: String?) {
        val e = prefs(c).edit()
        if (address == null) e.remove("address") else e.putString("address", address)
        e.apply()
    }

    fun lastLog(c: Context): String = prefs(c).getString("last", "") ?: ""

    fun setLastLog(c: Context, line: String) {
        prefs(c).edit().putString("last", line).apply()
    }
}

object AppLog {
    val lines = mutableStateListOf<String>()
    private val main = Handler(Looper.getMainLooper())

    fun add(context: Context, msg: String) {
        val t = SimpleDateFormat("MM/dd HH:mm:ss", Locale.JAPAN).format(Date())
        val line = "$t  $msg"
        Store.setLastLog(context, line)
        main.post {
            lines.add(0, line)
            while (lines.size > 50) lines.removeAt(lines.size - 1)
        }
    }
}
