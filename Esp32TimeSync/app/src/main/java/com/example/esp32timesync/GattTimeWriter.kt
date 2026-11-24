package com.example.esp32timesync

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

object Esp32Ble {
    // 基板側 (esp32/main.py) と同じ値にすること
    const val DEVICE_NAME = "ESP32CLK"
    val SERVICE_UUID: UUID = UUID.fromString("7d1c0001-7a3e-4c55-9f0b-5b2a1d6e0001")
    val TIME_UUID: UUID = UUID.fromString("7d1c0002-7a3e-4c55-9f0b-5b2a1d6e0001")
}

/**
 * 基板につないで「YYYY-MM-DD HH:MM:SS」(今のスマホの時刻) を1回書き込み、切断する。
 * 結果は onResult(成功か, メッセージ) で返す。
 */
class GattTimeWriter(
    private val context: Context,
    private val onResult: (Boolean, String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var finished = false

    private val timeout = Runnable { finish(false, "時間切れ（基板が見つからないか、応答がありません）") }

    @SuppressLint("MissingPermission")
    fun send(address: String) {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            finish(false, "Bluetoothがオフです")
            return
        }
        val device: BluetoothDevice = try {
            adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            finish(false, "登録された基板のアドレスが不正です")
            return
        }
        main.postDelayed(timeout, 20_000)
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    private fun finish(ok: Boolean, msg: String) {
        main.post {
            if (finished) return@post
            finished = true
            main.removeCallbacks(timeout)
            try {
                gatt?.disconnect()
                gatt?.close()
            } catch (_: Exception) {
            }
            gatt = null
            onResult(ok, msg)
        }
    }

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                finish(false, "接続に失敗しました (status=$status)")
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> g.discoverServices()
                BluetoothProfile.STATE_DISCONNECTED -> finish(false, "切断されました")
            }
        }

        @SuppressLint("MissingPermission")
        @Suppress("DEPRECATION")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val ch = g.getService(Esp32Ble.SERVICE_UUID)?.getCharacteristic(Esp32Ble.TIME_UUID)
            if (status != BluetoothGatt.GATT_SUCCESS || ch == null) {
                finish(false, "基板の時計サービスが見つかりません")
                return
            }
            val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val bytes = now.toByteArray(Charsets.US_ASCII)
            val started = if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(ch, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                    BluetoothStatusCodes.SUCCESS
            } else {
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ch.value = bytes
                g.writeCharacteristic(ch)
            }
            if (!started) finish(false, "書き込みを開始できませんでした")
            else lastSent = now
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) finish(true, "時刻を送りました ($lastSent)")
            else finish(false, "書き込みに失敗しました (status=$status)")
        }
    }

    private var lastSent = ""
}
