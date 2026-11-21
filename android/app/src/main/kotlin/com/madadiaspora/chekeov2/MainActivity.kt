package com.madadiaspora.chekeov2

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    companion object {
        private const val CHANNEL = "mcheck/overlay"
        private const val REQUEST_CAPTURE = 0x4D43
        private const val REQUEST_NOTIFICATIONS = 0x4E4F
    }

    private var pendingStart: MethodChannel.Result? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        val channel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
        OverlayService.channel = channel
        channel.setMethodCallHandler { call, result ->
            when (call.method) {
                "status" -> result.success(status())
                "requestOverlayPermission" -> {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                    result.success(null)
                }
                "startOverlay" -> startOverlayFlow(result)
                "stopOverlay" -> {
                    stopService(Intent(this, OverlayService::class.java))
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }
    }

    private fun status(): Map<String, Any> = mapOf(
        "overlayPermission" to Settings.canDrawOverlays(this),
        "overlayRunning" to OverlayService.isRunning,
        "sdkInt" to Build.VERSION.SDK_INT
    )

    private fun startOverlayFlow(result: MethodChannel.Result) {
        if (!Settings.canDrawOverlays(this)) {
            result.error("overlay_permission", "La superposition n'est pas autorisée", null)
            return
        }
        if (pendingStart != null) {
            result.error("busy", "Une demande est déjà en cours", null)
            return
        }
        pendingStart = result
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_CAPTURE) {
            val result = pendingStart
            pendingStart = null
            if (resultCode == Activity.RESULT_OK && data != null) {
                try {
                    OverlayService.start(this, resultCode, data)
                    result?.success(true)
                } catch (e: Exception) {
                    result?.error("capture_failed", e.message, null)
                }
            } else {
                result?.success(false)
            }
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    override fun onDestroy() {
        OverlayService.channel = null
        pendingStart = null
        super.onDestroy()
    }
}
