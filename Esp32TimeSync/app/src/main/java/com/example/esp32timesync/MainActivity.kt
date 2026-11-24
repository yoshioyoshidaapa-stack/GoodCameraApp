package com.example.esp32timesync

import android.Manifest
import android.bluetooth.le.ScanResult
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.util.regex.Pattern

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                App()
            }
        }
    }
}

private fun hasConnectPermission(ctx: Context) =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) ==
        PackageManager.PERMISSION_GRANTED

/** この端末で、基板の登録に必要な機能が使えるかを、日本語で説明する（診断用）。 */
private fun diagnose(ctx: Context): String {
    val pm = ctx.packageManager
    val companion = pm.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)
    val ble = pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
    return "端末の診断: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) / " +
        "BLE=${if (ble) "対応" else "非対応"} / " +
        "コンパニオン機能=${if (companion) "対応" else "非対応"}"
}

private fun startAssociation(ctx: Context, launch: (IntentSender) -> Unit) {
    try {
        val cdm = ctx.getSystemService(CompanionDeviceManager::class.java)
        if (cdm == null || !ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)) {
            AppLog.add(ctx, "この端末は、基板の自動登録の機能に対応していません。")
            return
        }
        val filter = BluetoothLeDeviceFilter.Builder()
            .setNamePattern(Pattern.compile(Regex.escape(Esp32Ble.DEVICE_NAME)))
            .build()
        val request = AssociationRequest.Builder()
            .addDeviceFilter(filter)
            .setSingleDevice(false)
            .build()
        val callback = object : CompanionDeviceManager.Callback() {
            // Android 13 以降はこちらが呼ばれる
            override fun onAssociationPending(intentSender: IntentSender) {
                try {
                    launch(intentSender)
                } catch (e: Throwable) {
                    AppLog.add(ctx, "登録画面を開けませんでした: $e")
                }
            }

            // Android 12 はこちらが呼ばれる
            @Deprecated("Android 12 用")
            override fun onDeviceFound(intentSender: IntentSender) {
                try {
                    launch(intentSender)
                } catch (e: Throwable) {
                    AppLog.add(ctx, "登録画面を開けませんでした: $e")
                }
            }

            override fun onFailure(error: CharSequence?) {
                AppLog.add(ctx, "登録できませんでした: $error")
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            cdm.associate(request, ContextCompat.getMainExecutor(ctx), callback)
        } else {
            @Suppress("DEPRECATION")
            cdm.associate(request, callback, null)
        }
        AppLog.add(ctx, "基板を探しています。基板の電源が入っているか確認してください")
    } catch (e: Throwable) {
        AppLog.add(ctx, "登録を始められませんでした: $e")
    }
}

@Composable
private fun App() {
    val ctx = LocalContext.current
    var address by remember { mutableStateOf(Store.address(ctx)) }
    var pendingRegister by remember { mutableStateOf(false) }

    val assocLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode != android.app.Activity.RESULT_OK) {
            AppLog.add(ctx, "登録がキャンセルされました")
            return@rememberLauncherForActivityResult
        }
        val found: String? = try {
            if (Build.VERSION.SDK_INT >= 33) {
                result.data
                    ?.getParcelableExtra(CompanionDeviceManager.EXTRA_ASSOCIATION, AssociationInfo::class.java)
                    ?.deviceMacAddress?.toString()
            } else {
                @Suppress("DEPRECATION")
                result.data
                    ?.getParcelableExtra<ScanResult>(CompanionDeviceManager.EXTRA_DEVICE)
                    ?.device?.address
            }
        } catch (e: Throwable) {
            AppLog.add(ctx, "登録結果の読み取りに失敗: $e")
            null
        }
        if (found == null) {
            AppLog.add(ctx, "基板のアドレスを取得できませんでした")
        } else {
            val addr = found.uppercase()
            Store.setAddress(ctx, addr)
            address = addr
            try {
                val cdm = ctx.getSystemService(CompanionDeviceManager::class.java)
                @Suppress("DEPRECATION")
                cdm.startObservingDevicePresence(addr)
                AppLog.add(ctx, "登録しました ($addr)。以後、基板が近くに来ると自動で時刻を送ります")
            } catch (e: Throwable) {
                AppLog.add(ctx, "自動検出の設定に失敗: $e")
            }
        }
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && pendingRegister) {
            pendingRegister = false
            startAssociation(ctx) { assocLauncher.launch(IntentSenderRequest.Builder(it).build()) }
        } else if (!granted) {
            AppLog.add(ctx, "Bluetooth(付近のデバイス)の許可が必要です")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("ESP32 時計合わせ", style = MaterialTheme.typography.headlineSmall)
        Text(diagnose(ctx), style = MaterialTheme.typography.bodySmall)
        Text(
            if (address == null) "基板はまだ登録されていません。"
            else "登録済みの基板: $address",
        )

        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                if (hasConnectPermission(ctx)) {
                    startAssociation(ctx) { assocLauncher.launch(IntentSenderRequest.Builder(it).build()) }
                } else {
                    pendingRegister = true
                    permLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                }
            },
        ) { Text("基板を登録する（最初の1回だけ）") }

        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            enabled = address != null,
            onClick = {
                val a = address ?: return@OutlinedButton
                if (!hasConnectPermission(ctx)) {
                    permLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    return@OutlinedButton
                }
                AppLog.add(ctx, "手動で時刻を送ります")
                GattTimeWriter(ctx) { ok, msg ->
                    AppLog.add(ctx, (if (ok) "成功: " else "失敗: ") + msg)
                }.send(a)
            },
        ) { Text("今すぐ時刻を送る（手動）") }

        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            enabled = address != null,
            onClick = {
                val a = address ?: return@OutlinedButton
                try {
                    val cdm = ctx.getSystemService(CompanionDeviceManager::class.java)
                    @Suppress("DEPRECATION")
                    cdm.stopObservingDevicePresence(a)
                    @Suppress("DEPRECATION")
                    cdm.disassociate(a)
                } catch (e: Exception) {
                    AppLog.add(ctx, "解除中のエラー: ${e.message}")
                }
                Store.setAddress(ctx, null)
                address = null
                AppLog.add(ctx, "登録を解除しました")
            },
        ) { Text("登録を解除する") }

        Text("記録", style = MaterialTheme.typography.titleMedium)
        val last = Store.lastLog(ctx)
        if (AppLog.lines.isEmpty() && last.isNotEmpty()) Text("前回: $last")
        AppLog.lines.forEach { Text(it) }
    }
}
