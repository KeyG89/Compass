package viali.pl.ekonomik.compass

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.animation.Animation
import android.view.animation.RotateAnimation
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.*
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.snackbar.Snackbar.LENGTH_INDEFINITE
import kotlin.math.absoluteValue

const val REQUEST_PERMISSIONS_REQUEST_CODE = 34
const val REQUEST_CHECK_SETTINGS = 1
const val TAG = "MainActivity"


class MainActivity : AppCompatActivity(), SensorEventListener {

    // Calculations
    private var bearingAngle: Float = 0.0F
    private var currentDistance: Float = 0.0F

    //Sensors
    private lateinit var sensorManager: SensorManager
    private lateinit var accelerometer: Sensor
    private lateinit var magnetometer: Sensor

    private var currentDegreeCompass = 0.0f
    private var currentDegreeBearing = 0.0f
    private var lastAccelerometer = FloatArray(3)
    private var lastMagnetometer = FloatArray(3)
    private var lastAccelerometerSet = false
    private var lastMagnetometerSet = false
    private var degreeCompass: Float = 0.0F

    //region SensorEventListener Implementation
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Do nothing
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor === accelerometer) {
            lowPassFilter(event.values, lastAccelerometer)
            lastAccelerometerSet = true
        } else if (event.sensor === magnetometer) {
            lowPassFilter(event.values, lastMagnetometer)
            lastMagnetometerSet = true
        }

        if (lastAccelerometerSet && lastMagnetometerSet) {
            val r = FloatArray(9)
            if (SensorManager.getRotationMatrix(r, null, lastAccelerometer, lastMagnetometer)) {
                val orientation = FloatArray(3)
                SensorManager.getOrientation(r, orientation)

                degreeCompass = (Math.toDegrees(orientation[0].toDouble()) + 360).toFloat() % 360


                val rotateAnimationCompass = RotateAnimation(
                    currentDegreeCompass,
                    -degreeCompass,
                    Animation.RELATIVE_TO_SELF, 0.5f,
                    Animation.RELATIVE_TO_SELF, 0.5f
                )
                rotateAnimationCompass.duration = 1000
                rotateAnimationCompass.fillAfter = true

                val rotateAnimationBearing = RotateAnimation(
                    currentDegreeBearing,
                    -degreeCompass,
                    Animation.RELATIVE_TO_SELF, 0.5f,
                    Animation.RELATIVE_TO_SELF, 0.5f
                )
                rotateAnimationBearing.duration = 1000
                rotateAnimationBearing.fillAfter = true

                ivCompass.startAnimation(rotateAnimationCompass)

                ivBearing.startAnimation(rotateAnimationBearing)
                currentDegreeCompass = -degreeCompass
                currentDegreeBearing = -degreeCompass + bearingAngle
            }
        }
    }
    //endregion

    // FLAGS
    private var mRequestingLocationUpdates: Boolean = false
    private var noPermissionEver: Boolean = false

    // Destination Location
    private var mDestinationLocation: Location? = null

    // Location
    private var mCurrentLocation: Location? = null
    private lateinit var mFusedLocationClient: FusedLocationProviderClient
    private lateinit var mLocationSettingsRequest: LocationSettingsRequest
    private lateinit var mSettingsClient: SettingsClient
    private lateinit var mLocationCallback: LocationCallback
    private lateinit var mLocationRequest: LocationRequest

    //UI
    private lateinit var etLatitude: EditText
    private lateinit var etLongitude: EditText
    private lateinit var ivCompass: ImageView
    private lateinit var ivBearing: ImageView
    private lateinit var btnNavigate: Button
    private lateinit var tvDegree: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvDegree = findViewById(R.id.tvDegree);
        etLatitude = findViewById(R.id.etLatitude)
        etLongitude = findViewById(R.id.etLongitude)
        ivCompass = findViewById(R.id.ivCompass)
        ivBearing = findViewById(R.id.ivBearing)
        btnNavigate = findViewById(R.id.btnNavigate)

        mFusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        mSettingsClient = LocationServices.getSettingsClient(this)

        initializeSensors()
        createLocationCallback()
        createLocationRequest()
        buildLocationSettingsRequest()

        btnNavigate.setOnClickListener { v ->
            if (etValidate()) {
                mRequestingLocationUpdates = true; startGPSUpdates(); ivBearing.visibility = View.VISIBLE
            } else {
                ivBearing.visibility = View.GONE
            }
        }
    }

    //region Sensors
    private fun initializeSensors() {
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    }

    private fun resumeSensors() {
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME)
        sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_GAME)
    }

    private fun pauseSensors() {
        sensorManager.unregisterListener(this, accelerometer)
        sensorManager.unregisterListener(this, magnetometer)
    }

    fun lowPassFilter(input: FloatArray, output: FloatArray) {
        val alpha = 0.05f
        for (i in input.indices) {
            output[i] = output[i] + alpha * (input[i] - output[i])
        }
    }

    //endregion

    //region Update UI

    private fun etValidate(): Boolean {

        var latitude: Double? = etLatitude.text.toString().toDoubleOrNull()
        var longitude: Double? = etLongitude.text.toString().toDoubleOrNull()

        if (latitude == null || longitude == null) {
            showSnackbar(R.string.warning_wrong_coordinates, android.R.string.ok, View.OnClickListener { })
            return false
        } else if (latitude.absoluteValue > 90) {
            showSnackbar(R.string.warning_wrong_latitude, android.R.string.ok, View.OnClickListener { })
            return false
        } else if (longitude.absoluteValue > 180) {
            showSnackbar(R.string.warning_wrong_longitude, android.R.string.ok, View.OnClickListener { })
            return false
        }

        initDestinationLocation()
        return true
    }

    private fun updateUI() {

        if (mDestinationLocation != null) {
            tvDegree.text = currentDistance.toString() + " " + resources.getString(R.string.m_to_destination) +
                    " (${mDestinationLocation!!.latitude}, ${mDestinationLocation!!.longitude})"
        } else {
            tvDegree.text = resources.getString(R.string.enter_destination_coordinates)

        }
    }

    //endregion

    //region Notification SnackBar
    private fun showSnackbar(snackStrId: Int, actionStrId: Int = 0, listener: View.OnClickListener? = null) {
        val snackbar = Snackbar.make(
            findViewById(android.R.id.content), getString(snackStrId),
            LENGTH_INDEFINITE
        )
        if (actionStrId != 0 && listener != null) {
            snackbar.setAction(getString(actionStrId), listener)
        }
        snackbar.show()
    }
    //endregion

    //region GPS Location Configuration
    fun createLocationCallback() {
        mLocationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult?) {
                super.onLocationResult(locationResult)
                mCurrentLocation = locationResult?.lastLocation
                setBearingAngle()
                setDistance()
                updateUI()
            }
        }
    }

    fun createLocationRequest() {
        mLocationRequest = LocationRequest.create().apply {
            interval = 1000
            fastestInterval = 300
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        }

    }

    fun buildLocationSettingsRequest() {
        mLocationSettingsRequest = LocationSettingsRequest.Builder()
            .addLocationRequest(mLocationRequest)
            .build()
    }

    fun startGPSUpdates() {
        if (!gpsCheck()) {
            showSnackbar(R.string.turn_on_gps, android.R.string.ok,
                View.OnClickListener {
                    // Turn on GPS
                    val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                    startActivity(intent)
                })
        } else {

            // Begin by checking if the device has the necessary location settings.
            mSettingsClient.checkLocationSettings(mLocationSettingsRequest)
                .addOnSuccessListener(this) {
                    Log.d(TAG, "startGPSUpdate() Success")

                    if (checkPermissions()) {
                        try {
                            mFusedLocationClient.requestLocationUpdates(
                                mLocationRequest,
                                mLocationCallback, Looper.myLooper()
                            )
                        } catch (e: SecurityException) {
                        }
                    }

                }
                .addOnFailureListener(this) { e ->
                    Log.d(TAG, "startGPSUpdate() Failure")
                    val statusCode = (e as ApiException).statusCode
                    when (statusCode) {
                        LocationSettingsStatusCodes.RESOLUTION_REQUIRED -> {
                            Log.i(
                                TAG,
                                "Location settings are not satisfied. Attempting to upgrade " + "location settings "
                            )

                            try {
                                val rae = e as ResolvableApiException
                                rae.startResolutionForResult(this@MainActivity, REQUEST_CHECK_SETTINGS)
                            } catch (sie: IntentSender.SendIntentException) {
                                Log.i(TAG, "PendingIntent unable to execute request.")
                            }


                        }
                        LocationSettingsStatusCodes.SETTINGS_CHANGE_UNAVAILABLE -> {
                            val errorMessage =
                                "Location settings are inadequate, and cannot be " + "fixed here. Fix in Settings."
                            Log.e(TAG, errorMessage)
                            Toast.makeText(this@MainActivity, errorMessage, Toast.LENGTH_LONG).show()
                            mRequestingLocationUpdates = false
                        }
                    }

                }
        }
    }

    fun stopGPSUpdates() {
        if (!mRequestingLocationUpdates) {
            return
        }
        mFusedLocationClient.removeLocationUpdates(mLocationCallback)
            .addOnCompleteListener(this) {
            }
    }

    fun gpsCheck(): Boolean {
        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val gpsEnabled = manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        return gpsEnabled
    }

    //endregion

    //region LifeCycle Block
    override fun onResume() {
        super.onResume()
        resumeSensors()

        if (mRequestingLocationUpdates && checkPermissions()) {
            startGPSUpdates()
        } else if (!checkPermissions()) {
            requestPermissions()
        }


    }

    override fun onPause() {
        super.onPause()
        pauseSensors()

    }

    override fun onStop() {
        super.onStop()
        stopGPSUpdates()
    }


    //endregion

    //region Managing Permissions

    // Coming back from Location Settings
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            // Check for the integer request code originally supplied to startResolutionForResult().
            REQUEST_CHECK_SETTINGS -> when (resultCode) {
                Activity.RESULT_OK -> {
                    Log.i(TAG, "User agreed to make required location settings changes."); mRequestingLocationUpdates =
                        true
                }


                Activity.RESULT_CANCELED -> {
                    Log.i(TAG, "User chose not to make required location settings changes.")
                    mRequestingLocationUpdates = false
                }
            }// Nothing to do. startLocationupdates() gets called in onResume again.
        }
    }

    // Requesting Permissions
    fun requestPermissions() {
        val shouldProvideRationale = ActivityCompat.shouldShowRequestPermissionRationale(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        )

        if (shouldProvideRationale) {
            Log.i(TAG, "Displaying permission rationale to provide additional context.")

            showSnackbar(R.string.permission_rationale, android.R.string.ok,
                View.OnClickListener {
                    // Request permission
                    ActivityCompat.requestPermissions(
                        this@MainActivity,
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                        REQUEST_PERMISSIONS_REQUEST_CODE
                    )
                })

        } else {
            Log.i(TAG, "Requesting permission")
            ActivityCompat.requestPermissions(
                this@MainActivity,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                REQUEST_PERMISSIONS_REQUEST_CODE
            )
        }
    }

    // Coming back from receiving permissions request
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS_REQUEST_CODE) {
            if (grantResults.size <= 0) {
                Log.i(TAG, "User interaction was cancelled.")
            } else if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (mRequestingLocationUpdates) {
                    Log.i(TAG, "Permission granted, updates requested, starting location updates")
                    startGPSUpdates()
                }
            } else {
                // PERMISSION DENIED
            }
        }
    }

    // Permission check
    fun checkPermissions(): Boolean {
        val permissionState = ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        return permissionState == PackageManager.PERMISSION_GRANTED
    }

    fun warnUserNoPermissions() {
        showSnackbar(R.string.permission_denied,
            R.string.settings, View.OnClickListener {
                // Build intent that displays the App settings screen.
                val intent = Intent()
                intent.action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                val uri = Uri.fromParts(
                    "package",
                    BuildConfig.APPLICATION_ID, null
                )
                intent.data = uri
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(intent)
            })

    }


//endregion

    //region Calculations

    fun initDestinationLocation() {
        //// RUN ONLY IF ET VALIDATES!!
        mDestinationLocation = Location("")
        mDestinationLocation!!.latitude = etLatitude.text.toString().toDouble()
        mDestinationLocation!!.longitude = etLongitude.text.toString().toDouble()
    }

    fun setBearingAngle() {

        if (mCurrentLocation != null && mDestinationLocation != null) {
            bearingAngle = mCurrentLocation!!.bearingTo(mDestinationLocation)
        } else {
            bearingAngle = 0F
        }
    }

    fun setDistance() {
        if (mCurrentLocation != null && mDestinationLocation != null) {
            currentDistance = mCurrentLocation!!.distanceTo(mDestinationLocation)
        } else {
            currentDistance = 0F
        }
    }

    //endregion


}
