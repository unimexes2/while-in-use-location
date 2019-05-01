/*
 * Copyright 2019 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.example.android.whileinuselocation

import android.Manifest
import android.content.pm.PackageManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.location.Location
import android.net.Uri
import android.os.IBinder
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager

import com.google.android.material.snackbar.Snackbar
/**
 *  This app allows a user to track their location. Because this app creates a foreground service
 *  (tied to a Notification) when the user navigates away from the app, it only needs "while in use"
 *  location permissions. That is, there is no need to ask for location all the time
 *  (which requires additional permissions in the manifest).
 *
 *  Note: Users have three options in Q+ regarding location:
 *
 *  * Allow all the time
 *  * Allow while app is in use, i.e., while app is in foreground
 *  * Not allow location at all
 *
 * It is generally recommended you only request "while in use" location permissions. If your app
 * does have a feature that requires background, request that permission in context and handle it
 * gracefully if the user denies the request or only allows "while-in-use".
 *
 * "Q" also now requires developers to specify foreground service type in the manifest (in this
 * case, "location").
 *
 * This sample uses a long-running bound and started service for location updates. The service is
 * aware of foreground status of this activity, which is the only bound client in
 * this sample. After requesting location updates and when the activity ceases to be in the
 * foreground, the service promotes itself to a foreground service and continues receiving location
 * updates. When the activity comes back to the foreground, the foreground service stops, and the
 * notification associated with that foreground service is removed.
 *
 * While the foreground service notification is displayed, the user has the option to launch the
 * activity from the notification. The user can also remove location updates directly from the
 * notification. This dismisses the notification and stops the service.
 */
class MainActivity : AppCompatActivity(), SharedPreferences.OnSharedPreferenceChangeListener {

    private var whileInUseLocationServiceBound = false

    // Provides location updates for while-in-use feature.
    private var whileInUseLocationService: WhileInUseLocationService? = null

    // Listens for location broadcasts from WhileInUseLocationService.
    private lateinit var whileInUseBroadcastReceiver: WhileInUseBroadcastReceiver

    private lateinit var sharedPreferences:SharedPreferences

    private lateinit var whileInUseLocationButton: Button
    private lateinit var allTheTimeLocationButton: Button

    private lateinit var outputTextView: TextView

    // Monitors connection to the while-in-use service.
    private val whileInUseServiceConnection = object : ServiceConnection {

        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as WhileInUseLocationService.LocalBinder
            whileInUseLocationService = binder.service
            whileInUseLocationServiceBound = true
        }

        override fun onServiceDisconnected(name: ComponentName) {
            whileInUseLocationService = null
            whileInUseLocationServiceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        whileInUseBroadcastReceiver = WhileInUseBroadcastReceiver()

        sharedPreferences =
            getSharedPreferences(getString(R.string.preference_file_key), Context.MODE_PRIVATE)

        whileInUseLocationButton = findViewById(R.id.while_in_use_location_button)
        allTheTimeLocationButton = findViewById(R.id.all_the_time_location_button)
        outputTextView = findViewById(R.id.output_text_view)

        whileInUseLocationButton.setOnClickListener {

            val enabled =
                sharedPreferences.getBoolean(SharedPreferenceUtil.KEY_WHILE_IN_USE_ENABLED, false)

            if (enabled) {
                whileInUseLocationService?.stopTrackingLocation()
            } else {
                if (!checkWhileInUsePermission()) {
                    requestWhileInUsePermissions()
                } else {
                    whileInUseLocationService?.startTrackingLocation()
                        ?: Log.d(TAG, "Service Not Bound")
                }
            }
        }

        allTheTimeLocationButton.setOnClickListener {
            // TODO: Add all the time logic.
        }
    }

    override fun onStart() {
        super.onStart()

        updateWhileInUseButtonsState(
            sharedPreferences.getBoolean(SharedPreferenceUtil.KEY_WHILE_IN_USE_ENABLED, false)
        )
        sharedPreferences.registerOnSharedPreferenceChangeListener(this)

        val serviceIntent = Intent(this, WhileInUseLocationService::class.java)
        bindService(serviceIntent, whileInUseServiceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()
        LocalBroadcastManager.getInstance(this).registerReceiver(
            whileInUseBroadcastReceiver,
            IntentFilter(WhileInUseLocationService.ACTION_NEW_WHILE_IN_USE_LOCATION_BROADCAST)
        )
    }

    override fun onPause() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(
            whileInUseBroadcastReceiver
        )
        super.onPause()
    }

    override fun onStop() {
        if (whileInUseLocationServiceBound) {
            unbindService(whileInUseServiceConnection)
            whileInUseLocationServiceBound = false
        }
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(this)

        super.onStop()
    }

    private fun checkWhileInUsePermission(): Boolean {
        return PackageManager.PERMISSION_GRANTED == ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String) {
        // Updates button states if new while in use location is added to SharedPreferences.
        if (key == SharedPreferenceUtil.KEY_WHILE_IN_USE_ENABLED) {
            updateWhileInUseButtonsState(
                sharedPreferences.getBoolean(SharedPreferenceUtil.KEY_WHILE_IN_USE_ENABLED, false)
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        Log.d(TAG, "onRequestPermissionResult")

        if (requestCode == REQUEST_WHILE_IN_USE_PERMISSIONS_REQUEST_CODE) {
            when {
                grantResults.isEmpty() ->
                    // If user interaction was interrupted, the permission request
                    // is cancelled and you receive empty arrays.
                    Log.d(TAG, "User interaction was cancelled.")

                grantResults[0] == PackageManager.PERMISSION_GRANTED ->
                    // Permission was granted.
                    whileInUseLocationService?.startTrackingLocation()

                else -> {
                    // Permission denied.
                    updateWhileInUseButtonsState(false)

                    Snackbar.make(
                        findViewById(R.id.activity_main),
                        R.string.permission_denied_explanation,
                        Snackbar.LENGTH_INDEFINITE
                    )
                        .setAction(R.string.settings) {
                            // Build intent that displays the App settings screen.
                            val intent = Intent()
                            intent.action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                            val uri = Uri.fromParts(
                                "package",
                                BuildConfig.APPLICATION_ID,
                                null
                            )
                            intent.data = uri
                            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            startActivity(intent)
                        }
                        .show()
                }
            }
        }
    }

    private fun requestWhileInUsePermissions() {

        val provideRationale =
            ActivityCompat.shouldShowRequestPermissionRationale(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )

        // If the user denied a previous request, but didn't check "Don't ask again", provide
        // additional rationale.
        if (provideRationale) {
            Snackbar.make(
                findViewById(R.id.activity_main),
                R.string.permission_rationale,
                Snackbar.LENGTH_INDEFINITE
            )
                .setAction(R.string.ok) {
                    // Request permission
                    ActivityCompat.requestPermissions(
                        this@MainActivity,
                        arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION),
                        REQUEST_WHILE_IN_USE_PERMISSIONS_REQUEST_CODE
                    )
                }
                .show()
        } else {
            Log.d(TAG, "Request permission")
            ActivityCompat.requestPermissions(
                this@MainActivity,
                arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION),
                REQUEST_WHILE_IN_USE_PERMISSIONS_REQUEST_CODE
            )
        }
    }

    private fun updateWhileInUseButtonsState(trackingLocation: Boolean) {
        if (trackingLocation) {
            whileInUseLocationButton.text = getString(R.string.disable_while_in_use_location)
        } else {
            whileInUseLocationButton.text = getString(R.string.enable_while_in_use_location)
        }
    }

    /**
     * Receiver for location broadcasts from [WhileInUseLocationService].
     */
    private inner class WhileInUseBroadcastReceiver : BroadcastReceiver() {

        override fun onReceive(context: Context, intent: Intent) {
            val location = intent.getParcelableExtra<Location>(
                WhileInUseLocationService.EXTRA_LOCATION
            )

            if (location != null) {
                val output = "While-in-use location: ${location.toText()}\n${outputTextView.text}"
                outputTextView.text = output
            }
        }
    }

    companion object {
        private val TAG = MainActivity::class.java.simpleName

        private const val REQUEST_WHILE_IN_USE_PERMISSIONS_REQUEST_CODE = 34
    }
}