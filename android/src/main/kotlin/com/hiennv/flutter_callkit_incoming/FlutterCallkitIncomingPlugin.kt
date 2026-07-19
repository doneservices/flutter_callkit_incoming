package com.hiennv.flutter_callkit_incoming

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.CallAudioState
import android.util.Log
import androidx.annotation.NonNull
import com.hiennv.flutter_callkit_incoming.Utils.Companion.reapCollection
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.*
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import java.lang.ref.WeakReference


/** FlutterCallkitIncomingPlugin */
@SuppressLint("LongLogTag")
class FlutterCallkitIncomingPlugin : FlutterPlugin, MethodCallHandler, ActivityAware,
    PluginRegistry.RequestPermissionsResultListener {
    companion object {

        const val EXTRA_CALLKIT_CALL_DATA = "EXTRA_CALLKIT_CALL_DATA"

        const val TAG = "FlutterCallkitIncomingPlugin"

        @SuppressLint("StaticFieldLeak")
        private lateinit var instance: FlutterCallkitIncomingPlugin

        fun getInstance(): FlutterCallkitIncomingPlugin? {
            if (hasInstance()) {
                return instance
            }
            return null
        }

        fun hasInstance(): Boolean {
            return ::instance.isInitialized
        }

        private val methodChannels = mutableMapOf<BinaryMessenger, MethodChannel>()
        private val eventChannels = mutableMapOf<BinaryMessenger, EventChannel>()
        private val eventHandlers = mutableMapOf<BinaryMessenger, EventCallbackHandler>()
        private val eventCallbacks = mutableListOf<WeakReference<CallkitEventCallback>>()

        fun sendEvent(event: String, body: Map<String, Any?>) {
            send(getInstance()?.context, event, body)
        }

        fun sendEvent(context: Context, event: String, body: Map<String, Any?>) {
            send(context.applicationContext, event, body)
        }

        fun sendEventCustom(event: String, body: Map<String, Any>) {
            send(getInstance()?.context, event, body)
        }

        fun acceptCallHandleCallback(bundle: Bundle) {
            Handler(Looper.getMainLooper()).postDelayed({
                val extra = bundle.getSerializable(CallkitConstants.EXTRA_CALLKIT_EXTRA)
                methodChannels.values.forEach {
                    it.invokeMethod("acceptCallHandle", extra)
                }
            }, 750)
        }

        fun invokeFlutterCallback(data: Any) {
            Handler(Looper.getMainLooper()).postDelayed({
                methodChannels.values.forEach {
                    it.invokeMethod("invokeFlutter", data)
                }
            }, 750)
        }

        /**
         * Send event to Flutter UI if there are active handlers, otherwise send to background
         * executor if registered.
         */
        private fun send(context: Context?, event: String, body: Map<String, Any?>) {
            val uiHandlers = eventHandlers.values.filter { it.hasListener() }
            if (uiHandlers.isNotEmpty()) {
                Log.d(TAG, "Sending UI event: $event")
                uiHandlers.forEach { it.send(event, body) }
            } else {
                Log.d(TAG, "Sending background event: $event (no UI handlers)")
                val callbackHandle = getPluginCallbackHandle(context) ?: 0L
                if (context != null && callbackHandle != 0L) {
                    CallkitBackgroundExecutor.start(context, callbackHandle)
                }
                CallkitBackgroundExecutor.send(event, body)
            }
        }

        /**
         * Register a callback to receive call events (accept/decline) natively.
         * This allows other plugins/services to handle call events
         * even when Flutter engine is terminated.
         */
        fun registerEventCallback(callback: CallkitEventCallback) {
            eventCallbacks.add(WeakReference(callback))
        }

        /**
         * Unregister an event callback.
         */
        fun unregisterEventCallback(callback: CallkitEventCallback) {
            eventCallbacks.removeAll { it.get() == callback || it.get() == null }
        }

        /**
         * Notify all registered event callbacks.
         * Called internally when a call event occurs.
         */
        internal fun notifyEventCallbacks(event: CallkitEventCallback.CallEvent, callData: android.os.Bundle) {
            eventCallbacks.reapCollection().forEach { callbackRef ->
                callbackRef.get()?.onCallEvent(event, callData)
            }
        }


        fun sharePluginWithRegister(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
            initSharedInstance(
                flutterPluginBinding.applicationContext,
                flutterPluginBinding.binaryMessenger
            )
        }

        fun initSharedInstance(context: Context, binaryMessenger: BinaryMessenger) {
            if (!::instance.isInitialized) {
                instance = FlutterCallkitIncomingPlugin()
            }
            // Restore instance fields that may have been nulled during a previous
            // detach. The static `instance` survives across FlutterEngine teardown
            // when the host process is kept alive (e.g. foreground services),
            // so `onAttachedToEngine` must refresh these fields every time;
            // otherwise background-isolate calls end up as silent no-ops.
            if (instance.callkitSoundPlayerManager == null) {
                instance.callkitSoundPlayerManager = CallkitSoundPlayerManager(context)
            }
            if (instance.callkitNotificationManager == null) {
                instance.callkitNotificationManager = CallkitNotificationManager(context, instance.callkitSoundPlayerManager)
            }
            if (instance.context == null) {
                instance.context = context
            } else {
                // Re-initialize managers if they were destroyed but instance still exists
                if (instance.callkitNotificationManager == null) {
                    instance.callkitSoundPlayerManager = CallkitSoundPlayerManager(context)
                    instance.callkitNotificationManager = CallkitNotificationManager(context, instance.callkitSoundPlayerManager)
                }
            }

            val channel = MethodChannel(binaryMessenger, "flutter_callkit_incoming")
            methodChannels[binaryMessenger] = channel
            channel.setMethodCallHandler(instance)

            val events = EventChannel(binaryMessenger, "flutter_callkit_incoming_events")
            eventChannels[binaryMessenger] = events
            val handler = EventCallbackHandler()
            eventHandlers[binaryMessenger] = handler
            events.setStreamHandler(handler)

        }

    }

    /// The MethodChannel that will the communication between Flutter and native Android
    ///
    /// This local reference serves to register the plugin with the Flutter Engine and unregister it
    /// when the Flutter Engine is detached from the Activity
    private var activity: Activity? = null
    private var context: Context? = null
    private var callkitNotificationManager: CallkitNotificationManager? = null
    private var callkitSoundPlayerManager: CallkitSoundPlayerManager? = null

    fun getCallkitNotificationManager(): CallkitNotificationManager? {
        return callkitNotificationManager
    }

    fun getCallkitSoundPlayerManager(): CallkitSoundPlayerManager? {
        return callkitSoundPlayerManager
    }

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        sharePluginWithRegister(flutterPluginBinding)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            InAppCallManager(flutterPluginBinding.applicationContext).registerPhoneAccount()
        }
    }

    public fun showIncomingNotification(data: Data) {
        data.from = "notification"
        //send BroadcastReceiver
        context?.sendBroadcast(
            CallkitIncomingBroadcastReceiver.getIntentIncoming(
                requireNotNull(context),
                data.toBundle()
            )
        )
    }

    public fun showMissCallNotification(data: Data) {
        callkitNotificationManager?.showMissCallNotification(data.toBundle())
    }

    public fun startCall(data: Data) {
        context?.sendBroadcast(
            CallkitIncomingBroadcastReceiver.getIntentStart(
                requireNotNull(context),
                data.toBundle()
            )
        )
    }

    public fun endCall(data: Data) {
        context?.sendBroadcast(
            CallkitIncomingBroadcastReceiver.getIntentEnded(
                requireNotNull(context),
                data.toBundle()
            )
        )
    }

    public fun endAllCalls() {
        val calls = getDataActiveCalls(context)
        calls.forEach {
            context?.sendBroadcast(
                CallkitIncomingBroadcastReceiver.getIntentEnded(
                    requireNotNull(context),
                    it.toBundle()
                )
            )
        }
        removeAllCalls(context)
    }

    fun sendEventCustom(body: Map<String, Any>) {
        send(context, CallkitConstants.ACTION_CALL_CUSTOM, body)
    }

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
        try {
            when (call.method) {
                "registerBackgroundHandler" -> {
                    val args = call.arguments as Map<*, *>
                    val pluginHandle = (args["pluginHandle"] as Number).toLong()
                    val userHandle = (args["userHandle"] as Number).toLong()
                    addBackgroundCallback(context, pluginHandle, userHandle)
                    CallkitBackgroundExecutor.start(requireNotNull(context), pluginHandle)
                    result.success(null)
                }

                "getBackgroundHandler" -> {
                    val handle = getUserCallback(context)
                    result.success(handle)
                }

                "setAcceptCallHandle" -> {
                    val args = call.arguments as? List<*>
                    if (args != null && args.size >= 2) {
                        val handle = args[0] as? Int ?: 0
                        val key = args[1] as? String ?: ""
                        saveHandle(context, key, handle)
                    }
                    result.success(null)
                }

                "showCallkitIncoming" -> {
                    val data = Data(call.arguments() ?: HashMap())
                    data.from = "notification"
                    //send BroadcastReceiver
                    context?.sendBroadcast(
                        CallkitIncomingBroadcastReceiver.getIntentIncoming(
                            requireNotNull(context),
                            data.toBundle()
                        )
                    )

                    result.success(true)
                }

                "showCallkitIncomingSilently" -> {
                    val data = Data(call.arguments() ?: HashMap())
                    data.from = "notification"

                    result.success(true)
                }

                "showMissCallNotification" -> {
                    val data = Data(call.arguments() ?: HashMap())
                    data.from = "notification"
                    callkitNotificationManager?.showMissCallNotification(data.toBundle())
                    result.success(true)
                }

                "startCall" -> {
                    val data = Data(call.arguments() ?: HashMap())
                    context?.sendBroadcast(
                        CallkitIncomingBroadcastReceiver.getIntentStart(
                            requireNotNull(context),
                            data.toBundle()
                        )
                    )

                    result.success(true)
                }

                "muteCall" -> {
                    val map = buildMap {
                        val args = call.arguments
                        if (args is Map<*, *>) {
                            putAll(args as Map<String, Any>)
                        }
                    }
                    sendEvent(CallkitConstants.ACTION_CALL_TOGGLE_MUTE, map)

                    result.success(true)
                }

                "holdCall" -> {
                    val map = buildMap {
                        val args = call.arguments
                        if (args is Map<*, *>) {
                            putAll(args as Map<String, Any>)
                        }
                    }
                    sendEvent(CallkitConstants.ACTION_CALL_TOGGLE_HOLD, map)

                    result.success(true)
                }

                "isMuted" -> {
                    result.success(true)
                }

                "endCall" -> {
                    val calls = getDataActiveCalls(context)
                    val data = Data(call.arguments() ?: HashMap())
                    val currentCall = calls.firstOrNull { it.id == data.id }
                    if (currentCall != null && context != null) {
                        if(currentCall.isAccepted) {
                            context?.sendBroadcast(
                                CallkitIncomingBroadcastReceiver.getIntentEnded(
                                    requireNotNull(context),
                                    currentCall.toBundle()
                                )
                            )
                        }else {
                            context?.sendBroadcast(
                                CallkitIncomingBroadcastReceiver.getIntentDecline(
                                    requireNotNull(context),
                                    currentCall.toBundle()
                                )
                            )
                        }
                    }
                    result.success(true)
                }

                "acceptIncomingCall" -> {
                    val requested = Data(call.arguments() ?: HashMap())
                    val current = getDataActiveCalls(context).firstOrNull { it.id == requested.id }
                        ?: requested
                    if (current.id.isEmpty() || context == null) {
                        result.success(false)
                    } else {
                        context?.sendBroadcast(
                            CallkitIncomingBroadcastReceiver.getIntentAccept(
                                requireNotNull(context),
                                current.toBundle()
                            )
                        )
                        result.success(true)
                    }
                }

                "dismissIncomingCall" -> {
                    val requested = Data(call.arguments() ?: HashMap())
                    val connection = CallkitConnection.find(requested.id)
                    val current = connection?.bundle?.let { Data.fromBundle(it) }
                        ?: getDataActiveCalls(context).firstOrNull { it.id == requested.id }
                        ?: requested
                    if (current.id.isEmpty() || current.isAccepted || context == null) {
                        result.success(false)
                    } else {
                        callkitNotificationManager?.clearIncomingNotification(
                            connection?.bundle ?: current.toBundle(),
                            false
                        )
                        CallkitNotificationService.stopService(requireNotNull(context))
                        connection?.markEnded()
                        removeCall(context, current)
                        result.success(true)
                    }
                }

                "callConnected" -> {
                    markCallConnected(call, result)
                }

                "markCallConnected" -> {
                    markCallConnected(call, result)
                }

                "setAudioRoute" -> {
                    val args = call.arguments as? Map<*, *>
                    val id = args?.get("id") as? String
                    val route = args?.get("route") as? String
                    val preserveExternal = args?.get("preserveExternalRoute") as? Boolean ?: false
                    val connection = id?.let { CallkitConnection.find(it) }
                    if (connection == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                        result.success(null)
                    } else {
                        val state = connection.callAudioState
                        val externalRoutes =
                            CallAudioState.ROUTE_BLUETOOTH or CallAudioState.ROUTE_WIRED_HEADSET
                        if (preserveExternal && state != null && state.route and externalRoutes != 0) {
                            result.success(true)
                        } else {
                            val expectedSpeaker = route == "speaker"
                            connection.setAudioRoute(
                                if (expectedSpeaker) CallAudioState.ROUTE_SPEAKER
                                else CallAudioState.ROUTE_EARPIECE
                            )
                            confirmAudioRoute(connection, expectedSpeaker, result, 0)
                        }
                    }
                }

                "endAllCalls" -> {
                    val calls = getDataActiveCalls(context)
                    calls.forEach {
                        if (it.isAccepted) {
                            context?.sendBroadcast(
                                CallkitIncomingBroadcastReceiver.getIntentEnded(
                                    requireNotNull(context),
                                    it.toBundle()
                                )
                            )
                        } else {
                            context?.sendBroadcast(
                                CallkitIncomingBroadcastReceiver.getIntentDecline(
                                    requireNotNull(context),
                                    it.toBundle()
                                )
                            )
                        }
                    }
                    removeAllCalls(context)
                    result.success(true)
                }

                "activeCalls" -> {
                    result.success(getDataActiveCallsForFlutter(context))
                }

                "getDevicePushTokenVoIP" -> {
                    result.success("")
                }

                "silenceEvents" -> {
                    val silence = call.arguments as? Boolean ?: false
                    CallkitIncomingBroadcastReceiver.silenceEvents = silence
                    result.success(true)
                }

                "requestNotificationPermission" -> {
                    val map = buildMap {
                        val args = call.arguments
                        if (args is Map<*, *>) {
                            putAll(args as Map<String, Any>)
                        }
                    }
                    callkitNotificationManager?.requestNotificationPermission(activity, map)
                    result.success(true)
                }

                "requestFullIntentPermission" -> {
                    callkitNotificationManager?.requestFullIntentPermission(activity)
                    result.success(true)
                }

                "canUseFullScreenIntent" -> {
                    result.success(callkitNotificationManager?.canUseFullScreenIntent() ?: true)
                }

                // EDIT - clear the incoming notification/ring (after accept/decline/timeout)
                "hideCallkitIncoming" -> {
                    val data = Data(call.arguments() ?: HashMap())
                    callkitSoundPlayerManager?.stop()
                    callkitNotificationManager?.clearIncomingNotification(data.toBundle(), false)
                    result.success(true)
                }

                "endNativeSubsystemOnly" -> {
                    result.success(true)
                }

            }
        } catch (error: Exception) {
            result.error("error", error.message, "")
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        methodChannels.remove(binding.binaryMessenger)?.setMethodCallHandler(null)
        eventChannels.remove(binding.binaryMessenger)?.setStreamHandler(null)
        eventHandlers.remove(binding.binaryMessenger)

        // Only destroy and null the shared managers when the LAST engine detaches.
        // When multiple engines are attached (e.g. main UI engine + FCM background
        // isolate engine), tearing down the main engine must not pull the managers
        // out from under the background isolate that still needs them.
        if (methodChannels.isEmpty() && eventChannels.isEmpty()) {
            instance.callkitSoundPlayerManager?.destroy()
            instance.callkitNotificationManager?.destroy()
            instance.callkitSoundPlayerManager = null
            instance.callkitNotificationManager = null
        }
        Log.d(TAG, "onDetachedFromEngine")
    }

    private fun markCallConnected(call: MethodCall, result: Result) {
        val calls = getDataActiveCalls(context)
        val data = Data(call.arguments() ?: HashMap())
        val currentCall = calls.firstOrNull { it.id == data.id }
        if (currentCall != null && context != null) {
            context?.sendBroadcast(
                CallkitIncomingBroadcastReceiver.getIntentConnected(
                    requireNotNull(context),
                    currentCall.toBundle()
                )
            )
        }
        result.success(currentCall != null)
    }

    private fun confirmAudioRoute(
        connection: CallkitConnection,
        expectedSpeaker: Boolean,
        result: Result,
        attempt: Int,
    ) {
        val state = connection.callAudioState
        if (state != null) {
            val actualSpeaker = state.route and CallAudioState.ROUTE_SPEAKER != 0
            if (actualSpeaker == expectedSpeaker || attempt >= 5) {
                result.success(actualSpeaker)
                return
            }
        } else if (attempt >= 5) {
            result.success(null)
            return
        }
        Handler(Looper.getMainLooper()).postDelayed(
            { confirmAudioRoute(connection, expectedSpeaker, result, attempt + 1) },
            100L,
        )
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        instance.context = binding.activity.applicationContext
        instance.activity = binding.activity
        binding.addRequestPermissionsResultListener(this)
    }

    override fun onDetachedFromActivityForConfigChanges() {
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        instance.context = binding.activity.applicationContext
        instance.activity = binding.activity
        binding.addRequestPermissionsResultListener(this)
    }

    override fun onDetachedFromActivity() {
        // Keep instance.context alive. It is the applicationContext, shared by
        // every engine attachment and safe to hold for the lifetime of the JVM.
        // Nulling it here would break background-isolate method channel calls
        // (showCallkitIncoming relies on `context?.sendBroadcast(...)`) whenever
        // the activity is destroyed while a cached background engine keeps the
        // static `instance` alive.
        instance.activity = null
    }

    class EventCallbackHandler : EventChannel.StreamHandler {

        @Volatile
        private var eventSink: EventChannel.EventSink? = null

        fun hasListener(): Boolean = eventSink != null

        override fun onListen(arguments: Any?, sink: EventChannel.EventSink) {
            eventSink = sink
        }

        fun send(event: String, body: Map<String, Any?>) {
            val data = mapOf(
                "event" to event,
                "body" to body
            )
            Handler(Looper.getMainLooper()).post {
                eventSink?.success(data)
            }
        }

        override fun onCancel(arguments: Any?) {
            eventSink = null
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ): Boolean {
        instance.callkitNotificationManager?.onRequestPermissionsResult(
            instance.activity,
            requestCode,
            grantResults
        )
        return true
    }


}
