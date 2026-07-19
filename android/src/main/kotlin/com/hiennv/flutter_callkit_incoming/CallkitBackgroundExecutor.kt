package com.hiennv.flutter_callkit_incoming

import android.content.Context
import android.util.Log
import io.flutter.FlutterInjector
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.embedding.engine.loader.FlutterLoader
import io.flutter.plugin.common.MethodChannel
import io.flutter.view.FlutterCallbackInformation

object CallkitBackgroundExecutor {
    private const val TAG = "CallkitBGExecutor"
    private const val CHANNEL = "flutter_callkit_incoming_background"

    @Volatile
    private var backgroundFlutterEngine: FlutterEngine? = null

    private var backgroundChannel: MethodChannel? = null

    private val queuedEvents = ArrayDeque<Pair<String, Map<String, Any?>>>()
    private var isDelivering = false

    @Volatile
    private var initialized = false

    val registered: Boolean
        get() = backgroundFlutterEngine != null

    fun start(context: Context, pluginCallbackHandle: Long) {
        if (backgroundFlutterEngine != null) {
            Log.d(TAG, "Background engine already running")
            return
        }

        val appCtx = context.applicationContext

        val loader: FlutterLoader = FlutterInjector.instance().flutterLoader()
        loader.startInitialization(appCtx)
        loader.ensureInitializationComplete(appCtx, null)

        backgroundFlutterEngine = FlutterEngine(appCtx)

        val callbackInfo =
            FlutterCallbackInformation.lookupCallbackInformation(pluginCallbackHandle)
        if (callbackInfo == null) {
            Log.e(TAG, "Background callback handle could not be resolved")
            backgroundFlutterEngine?.destroy()
            backgroundFlutterEngine = null
            return
        }

        val args = DartExecutor.DartCallback(
            appCtx.assets,
            loader.findAppBundlePath(),
            callbackInfo
        )

        backgroundChannel = MethodChannel(
            backgroundFlutterEngine!!.dartExecutor.binaryMessenger,
            CHANNEL
        )
        backgroundChannel!!.setMethodCallHandler { call, result ->
            if (call.method == "initialized") {
                initialized = true
                result.success(null)
                drain()
            } else {
                result.notImplemented()
            }
        }
        backgroundFlutterEngine!!.dartExecutor.executeDartCallback(args)

        Log.d(TAG, "Background engine started")
    }

    fun send(event: String, body: Map<String, Any?>) {
        if (backgroundFlutterEngine == null) {
            Log.e(TAG, "Background engine not started, cannot send event: $event")
            return
        }
        synchronized(queuedEvents) {
            queuedEvents.add(event to body)
        }
        drain()
    }

    private fun drain() {
        if (!initialized) return
        val next = synchronized(queuedEvents) {
            if (isDelivering || queuedEvents.isEmpty()) {
                null
            } else {
                isDelivering = true
                queuedEvents.removeFirst()
            }
        } ?: return
        val channel = requireNotNull(backgroundChannel)
        channel.invokeMethod(next.first, next.second, object : MethodChannel.Result {
            override fun success(result: Any?) = completeDelivery()

            override fun error(code: String, message: String?, details: Any?) {
                Log.e(TAG, "Background handler failed for ${next.first}: $message")
                completeDelivery()
            }

            override fun notImplemented() {
                Log.e(TAG, "Background handler unavailable for ${next.first}")
                completeDelivery()
            }
        })
    }

    private fun completeDelivery() {
        synchronized(queuedEvents) {
            isDelivering = false
        }
        drain()
    }
}
