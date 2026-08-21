package com.example.nacotavpn

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.annotation.NonNull
import id.laskarmedia.openvpn_flutter.OpenVPNFlutterPlugin
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    companion object {
        private const val TAG = "NativeOpenVPN"
        private const val CHANNEL = "native_openvpn"

        private const val SERVICE_CLASS_NAME =
            "de.blinkt.openvpn.core.OpenVPNService"

        private const val ACTION_START_SERVICE =
            "de.blinkt.openvpn.START_SERVICE"
    }

    private var isBound = false
    private var pendingResult: MethodChannel.Result? = null

    private val handler = Handler(Looper.getMainLooper())

    private val connection = object : ServiceConnection {

        override fun onServiceConnected(
            name: ComponentName?,
            binder: IBinder?
        ) {
            Log.d(TAG, "Force disconnect: onServiceConnected")
            Log.d(TAG, "Binder = $binder")

            isBound = true

            var stopVpnResult = false

            try {
                if (binder == null) {
                    Log.e(TAG, "Binder is null")
                } else {

                    // LocalBinder -> OpenVPNService
                    val binderClass = binder.javaClass

                    Log.d(
                        TAG,
                        "Binder class = ${binderClass.name}"
                    )

                    val getServiceMethod =
                        binderClass.getDeclaredMethod("getService")

                    getServiceMethod.isAccessible = true

                    val service =
                        getServiceMethod.invoke(binder)

                    if (service == null) {
                        Log.e(TAG, "getService() returned null")
                    } else {

                        Log.d(
                            TAG,
                            "Got service = ${service.javaClass.name}"
                        )


                        val stopMethod =
                            service.javaClass.getDeclaredMethod(
                                "stopVPN",
                                Boolean::class.javaPrimitiveType
                            )

                        stopMethod.isAccessible = true

                        stopVpnResult =
                            stopMethod.invoke(
                                service,
                                false
                            ) as? Boolean ?: false

                        Log.d(
                            TAG,
                            "stopVPN(false) returned = $stopVpnResult"
                        )
                    }
                }

            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "stopVPN direct call failed",
                    e
                )
            }


            try {

                val serviceClass =
                    Class.forName(SERVICE_CLASS_NAME)

                val stopIntent =
                    Intent(
                        this@MainActivity,
                        serviceClass
                    )

                val stopServiceResult =
                    stopService(stopIntent)

                Log.d(
                    TAG,
                    "stopService() returned = $stopServiceResult"
                )

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "stopService failed",
                    e
                )
            }

            unbindSafely()

            Log.d(
                TAG,
                "Force disconnect finished"
            )

            pendingResult?.success(
                stopVpnResult
            )

            pendingResult = null
        }

        override fun onServiceDisconnected(
            name: ComponentName?
        ) {
            Log.d(
                TAG,
                "onServiceDisconnected"
            )

            isBound = false
        }
    }

    override fun configureFlutterEngine(
        @NonNull flutterEngine: FlutterEngine
    ) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            CHANNEL
        ).setMethodCallHandler { call, result ->

            when (call.method) {

                "disconnect" -> {
                    forceDisconnect(result)
                }

                else -> {
                    result.notImplemented()
                }
            }
        }
    }

    private fun forceDisconnect(
        result: MethodChannel.Result
    ) {

        Log.d(
            TAG,
            "========== FORCE DISCONNECT =========="
        )

        if (pendingResult != null) {

            Log.w(
                TAG,
                "Disconnect already running"
            )

            result.error(
                "BUSY",
                "A disconnect operation is already running",
                null
            )

            return
        }

        pendingResult = result

        try {

            val serviceClass =
                Class.forName(SERVICE_CLASS_NAME)

            Log.d(
                TAG,
                "Found service class = ${serviceClass.name}"
            )

            val intent =
                Intent(
                    this,
                    serviceClass
                )

            /*
             * ICS OpenVPN برای bind شدن به
             * LocalBinder از این action استفاده می‌کند.
             */
            intent.action = ACTION_START_SERVICE

            val bound =
                bindService(
                    intent,
                    connection,
                    0
                )

            Log.d(
                TAG,
                "bindService() = $bound"
            )

            /*
             * اگر سرویس قابل bind نبود،
             * مستقیماً stopService را امتحان می‌کنیم.
             */
            if (!bound) {

                Log.w(
                    TAG,
                    "Could not bind to OpenVPNService"
                )

                try {

                    val stopped =
                        stopService(intent)

                    Log.d(
                        TAG,
                        "Fallback stopService() = $stopped"
                    )

                    pendingResult?.success(
                        stopped
                    )

                } catch (e: Exception) {

                    Log.e(
                        TAG,
                        "Fallback stopService failed",
                        e
                    )

                    pendingResult?.error(
                        "FORCE_DISCONNECT_FAILED",
                        e.message,
                        null
                    )
                }

                pendingResult = null
            }

        } catch (e: ClassNotFoundException) {

            Log.e(
                TAG,
                "OpenVPNService class not found",
                e
            )

            pendingResult = null

            result.error(
                "CLASS_NOT_FOUND",
                "OpenVPNService was not found",
                null
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Force disconnect failed",
                e
            )

            pendingResult = null

            result.error(
                "FORCE_DISCONNECT_FAILED",
                e.message,
                null
            )
        }
    }

    private fun unbindSafely() {

        if (!isBound) {
            return
        }

        try {

            unbindService(connection)

            Log.d(
                TAG,
                "Service unbound"
            )

        } catch (e: Exception) {

            Log.w(
                TAG,
                "unbindService failed",
                e
            )
        }

        isBound = false
    }

    override fun onDestroy() {

        unbindSafely()

        super.onDestroy()
    }

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {

        OpenVPNFlutterPlugin.connectWhileGranted(
            requestCode == 24 &&
                    resultCode == RESULT_OK
        )

        super.onActivityResult(
            requestCode,
            resultCode,
            data
        )
    }
}