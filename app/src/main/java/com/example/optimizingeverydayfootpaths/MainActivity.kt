package com.example.optimizingeverydayfootpaths

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.location.Location
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.example.optimizingeverydayfootpaths.data.LocationData
import com.example.optimizingeverydayfootpaths.data.LocationDatabase
import com.example.optimizingeverydayfootpaths.data.LocationDatabaseSingleton
import com.example.optimizingeverydayfootpaths.data.LocationViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

// Data classes for parsing OpenRouteService API response
data class RouteResponse(
    val features: List<Feature>
)

data class Feature(
    val geometry: Geometry,
    val properties: Properties
)

data class Geometry(
    val coordinates: List<List<Double>>,
    val type: String
)

data class Properties(
    val segments: List<Segment>,
    val summary: Summary
)

data class Segment(
    val distance: Double,
    val duration: Double,
    val steps: List<Step>
)

data class Summary(
    val distance: Double,
    val duration: Double
)

data class Step(
    val distance: Double,
    val duration: Double,
    val type: Int,
    val instruction: String,
    val name: String,
    val wayPoints: List<Int>
)

// Retrofit interface for OpenRouteService API
interface OpenRouteServiceApi {

    @GET("v2/directions/foot-walking")
    suspend fun getWalkingRoute(
        @Query("api_key") apiKey: String,
        @Query("start") start: String,
        @Query("end") end: String
    ): RouteResponse
}

// Singleton object to manage Retrofit instance
object OpenRouteService {
    private const val BASE_URL = "https://api.openrouteservice.org/"

    val api: OpenRouteServiceApi by lazy {
        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        retrofit.create(OpenRouteServiceApi::class.java)
    }
}

class MainActivity : AppCompatActivity() {

    // UI elements and overlays
    private lateinit var map: MapView
    private lateinit var toggleButton: Button
    private lateinit var completePath: Polyline

    // Database and ViewModel
    private lateinit var locationViewModel: LocationViewModel
    private lateinit var db: LocationDatabase

    // Constants and thresholds for location processing
    private val recordDelay = 200 // Delay in seconds for segmenting paths
    private val stationaryRadius = 20.0 // Stationary radius in meters
    private val maxStationaryRadius = 25.0 // Maximum stationary radius in meters
    private val minSegmentLength = 300.0 // Minimum segment length in meters
    private val walkingSpeedThreshold = 7.0 / 3.6 // Walking speed threshold in m/s

    // Lists to store segmented paths
    private val walkingSegments = mutableListOf<List<Location>>()
    private val fasterSegments = mutableListOf<List<Location>>()

    // Tracking and location management
    private var isTracking = false
    private val locationOverlay by lazy { MyLocationNewOverlay(map) }
    private val locations = mutableListOf<Location>()
    private val segments = mutableListOf<List<Location>>()
    private val routes = mutableListOf<Geometry>() // To store route geometries

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Initialize configuration and layout
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
        setContentView(R.layout.activity_main)

        // Set up database and UI elements
        db = LocationDatabaseSingleton.getDatabase(this)
        map = findViewById(R.id.map)
        toggleButton = findViewById(R.id.btnToggleLocation)
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.overlays.add(locationOverlay)
        map.controller.setZoom(17.0)

        // Toggle tracking on button click
        toggleButton.setOnClickListener {
            isTracking = !isTracking
            if (isTracking) {
                startLocationService()
                toggleButton.text = "Stop Location Updates"
            } else {
                stopLocationService()
                toggleButton.text = "Start Location Updates"
            }
        }

        // Handle data deletion on button click
        findViewById<Button>(R.id.btnDeleteLocations).setOnClickListener {
            deleteAllData()
        }

        // Handle path segmentation and display on button click
        findViewById<Button>(R.id.btnSegmentPath).setOnClickListener {
            segmentAndDisplayPaths()
            fetchAndDisplayRoutes()
        }

        // Initialize the complete path polyline
        completePath = Polyline().apply {
            outlinePaint.strokeWidth = 10f
            outlinePaint.style = Paint.Style.FILL
            outlinePaint.color = Color.BLACK
            outlinePaint.strokeCap = Paint.Cap.ROUND
            outlinePaint.strokeJoin = Paint.Join.MITER
            title = "Complete Path"
            map.overlayManager.add(this)
        }

        // Initialize ViewModel and observe location data
        locationViewModel = ViewModelProvider(this)[LocationViewModel::class.java]
        locationViewModel.getAllLocations(db).observe(this) { locations ->
            updatePolyline(locations)
        }
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
    }

    override fun onPause() {
        super.onPause()
        map.onPause()
    }

    // Update the polyline on the map with location data from the database
    private fun updatePolyline(locations: List<LocationData>) {
        // Get the current list of GeoPoints from the Polyline
        val currentPoints = completePath.actualPoints

        // Remove all existing points if they are different from the new list
        if (currentPoints != locations.map { GeoPoint(it.latitude, it.longitude) }) {
            // Clear existing points
            completePath.setPoints(emptyList())

            // Add new points from the database
            locations.forEach { locationData ->
                completePath.addPoint(GeoPoint(locationData.latitude, locationData.longitude))
            }
            map.invalidate() // Update the map
        }
    }

    // Start the location tracking service
    private fun startLocationService() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val intent = Intent(this, LocationService::class.java)
            startForegroundService(intent)
        } else {
            val intent = Intent(this, LocationService::class.java)
            startService(intent)
        }
        // Enable location overlay and follow the user's location
        locationOverlay.enableMyLocation()
        locationOverlay.enableFollowLocation()
        map.controller.animateTo(locationOverlay.myLocation)
    }

    // Stop the location tracking service
    private fun stopLocationService() {
        val intent = Intent(this, LocationService::class.java)
        stopService(intent)
    }

    // Segment and display paths on the map based on stored location data
    private fun segmentAndDisplayPaths() {
        map.overlayManager.clear()
        map.overlayManager.add(locationOverlay) // Re-add location overlay
        map.overlayManager.add(completePath)

        lifecycleScope.launch(Dispatchers.IO) {
            val locationDataList = db.locationDataDao().getAllLocationsDirect()

            if (locationDataList.isNotEmpty()) {
                // Populate the locations list
                locations.clear()
                locations.addAll(locationDataList.map { locationData ->
                    Location("").apply {
                        latitude = locationData.latitude
                        longitude = locationData.longitude
                        time = locationData.timestamp // Assuming LocationData has a timestamp field
                    }
                })

                withContext(Dispatchers.Main) {
                    // Proceed with segmentation and display
                    val (walkingSegments, fasterSegments) = segmentRoutes(locations)

                    displaySegments(walkingSegments, Color.parseColor("#3385ff"), 20f)
                    displaySegments(fasterSegments, Color.parseColor("#ff9900"), 15f)

                    map.invalidate() // Redraw the map
                }
            } else {
                withContext(Dispatchers.Main) {
                    // Handle the case where no locations are available
                    Toast.makeText(this@MainActivity, "No locations available for segmentation.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Display the segmented paths on the map
    private fun displaySegments(segments: List<List<Location>>, color: Int, strokeWidth: Float) {
        segments.forEach { segment ->
            val polyline = Polyline().apply {
                outlinePaint.strokeWidth = strokeWidth
                outlinePaint.style = Paint.Style.FILL
                outlinePaint.color = color
                outlinePaint.strokeCap = Paint.Cap.ROUND
                outlinePaint.strokeJoin = Paint.Join.MITER
                segment.forEach { addPoint(GeoPoint(it.latitude, it.longitude)) }
            }
            map.overlayManager.add(polyline)
        }
    }

    // Fetch routes from OpenRouteService API and display on the map
    private fun fetchAndDisplayRoutes() {
        lifecycleScope.launch(Dispatchers.IO) {
            walkingSegments.forEach { segment ->
                val geometry = fetchRoute(segment)
                geometry?.let { routes.add(it) } // Store the geometry if it exists

                launch(Dispatchers.Main) {
                    geometry?.let {
                        drawRouteOnMap(it, map)
                    }
                }
            }
        }
    }

    // Draw the fetched route on the map
    private fun drawRouteOnMap(geometry: Geometry, map: MapView) {
        val polyline = Polyline().apply {
            outlinePaint.strokeWidth = 20f
            outlinePaint.style = Paint.Style.FILL
            outlinePaint.color = Color.parseColor("#9933ff")
            outlinePaint.strokeCap = Paint.Cap.ROUND
            outlinePaint.strokeJoin = Paint.Join.MITER
            geometry.coordinates.forEach {
                addPoint(GeoPoint(it[1], it[0]))
            }
        }
        map.overlayManager.add(polyline)
        map.invalidate()
    }

    // Fetch route data from the OpenRouteService API for a given segment
    private suspend fun fetchRoute(segment: List<Location>): Geometry? {
        if (segment.isEmpty()) return null

        // Convert the first and last locations of the segment to the required "start" and "end" format
        val startLocation = segment.firstOrNull() ?: return null
        val endLocation = segment.lastOrNull() ?: return null

        val start = "${startLocation.longitude},${startLocation.latitude}"
        val end = "${endLocation.longitude},${endLocation.latitude}"

        val apiKey = "YOUR_KEY" // Replace with your actual API key

        return try {
            val response = OpenRouteService.api.getWalkingRoute(apiKey, start, end)
            response.features.firstOrNull()?.geometry
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // Segment routes based on location data and classify into walking and faster segments
    private fun segmentRoutes(locations: List<Location>): Pair<List<List<Location>>, List<List<Location>>> {
        walkingSegments.clear() // Clear previous walking segments
        fasterSegments.clear() // Clear previous faster segments

        var i = 0
        var startPoint: Location = locations.first() // Initialize startPoint with the first location

        while (i < locations.size) {
            val pointA = locations[i]
            var pointB: Location? = null
            var pointC: Location? = null

            // Step 1: Search for pointB and pointC
            for (j in i + 1 until locations.size) {
                val candidate = locations[j]
                val timeDifference = (candidate.time - pointA.time) / 1000.0 // in seconds
                val distance = pointA.distanceTo(candidate)

                if (distance > maxStationaryRadius) {
                    break // Exit the loop if distance exceeds maxStationaryRadius
                }

                if (timeDifference >= recordDelay && distance <= stationaryRadius) {
                    pointB = candidate
                    break
                }
            }

            if (pointB != null) {
                for (j in i + 1 until locations.size) {
                    val candidate = locations[j]
                    val distance = pointA.distanceTo(candidate)

                    if (distance > maxStationaryRadius) {
                        break // Exit the loop if distance exceeds maxStationaryRadius
                    }

                    if (distance <= maxStationaryRadius) {
                        pointC = candidate
                    }
                }
            }

            // Handle the segmentation logic
            if (pointC != null) {
                val currentSegment = segmentOf(startPoint, pointA)
                classifySegment(currentSegment)
                startPoint = pointC
                i = locations.indexOf(pointC) // Move i to the index of pointC
            } else {
                // If pointA has no pointC but there is a startPoint, just move to the next point
                i++
            }
        }

        // Ensure any remaining segment is classified
        val finalSegment = segmentOf(startPoint, locations.last())
        classifySegment(finalSegment)

        return Pair(walkingSegments, fasterSegments)
    }

    // Filter locations to create a segment between two points
    private fun segmentOf(start: Location, end: Location): List<Location> {
        return locations.filter { it.time >= start.time && it.time <= end.time }
    }

    // Classify a segment based on average speed into walking or faster segments
    private fun classifySegment(segment: List<Location>) {
        if (segment.isEmpty()) return

        val segmentLength = calculateSegmentLength(segment)
        if (segmentLength < minSegmentLength) return

        val avgSpeed = calculateAverageSpeed(segment)
        if (avgSpeed <= walkingSpeedThreshold) {
            walkingSegments.add(segment)
        } else {
            processFasterSegment(segment, walkingSegments, fasterSegments)
        }
    }

    // Calculate the length of a segment
    private fun calculateSegmentLength(segment: List<Location>): Double {
        var length = 0.0
        for (i in 1 until segment.size) {
            length += segment[i].distanceTo(segment[i - 1])
        }
        return length
    }

    // Process segments that are faster than walking speed and handle edge cases
    private fun processFasterSegment(
        segment: List<Location>,
        walkingSegments: MutableList<List<Location>>,
        fasterSegments: MutableList<List<Location>>
    ) {
        if (segment.isEmpty()) return

        val minLegLength = 25.0 // Minimum length for a leg to be considered

        // Define sub-segments for the first and last legs
        var firstLeg: List<Location>? = null
        var lastLeg: List<Location>? = null

        // Check the first leg
        for (i in 1 until segment.size) {
            val subSegment = segment.subList(0, i + 1)
            val avgSpeed = calculateAverageSpeed(subSegment)

            if (avgSpeed <= walkingSpeedThreshold) {
                firstLeg = subSegment
            } else {
                break // Stop checking further once the average speed exceeds the threshold
            }
        }

        // Check the last leg
        for (i in segment.size - 2 downTo 0) {
            val subSegment = segment.subList(i, segment.size)
            val avgSpeed = calculateAverageSpeed(subSegment)

            if (avgSpeed <= walkingSpeedThreshold) {
                lastLeg = subSegment
            } else {
                break // Stop checking further once the average speed exceeds the threshold
            }
        }

        // Process the middle faster segment, excluding any identified walking legs
        val middleSegmentStartIndex = firstLeg?.lastOrNull()?.let { segment.indexOf(it) + 1 } ?: 0
        val middleSegmentEndIndex = lastLeg?.firstOrNull()?.let { segment.indexOf(it) } ?: segment.size

        // If both legs are too short and the middle segment wasn't added, treat the entire segment as a faster segment
        if ((firstLeg == null || calculateSegmentLength(firstLeg) < minLegLength) &&
            (lastLeg == null || calculateSegmentLength(lastLeg) < minLegLength)
        ) {
            fasterSegments.add(segment)
        } else {
            if (middleSegmentStartIndex < middleSegmentEndIndex) {
                val middleSegment = segment.subList(middleSegmentStartIndex, middleSegmentEndIndex)
                fasterSegments.add(middleSegment)
            }

            // Add the identified walking legs to the walking segments if they are long enough
            if (firstLeg != null && calculateSegmentLength(firstLeg) >= minLegLength) {
                walkingSegments.add(firstLeg)
            }

            if (lastLeg != null && calculateSegmentLength(lastLeg) >= minLegLength) {
                walkingSegments.add(lastLeg)
            }
        }
    }

    // Calculate the average speed of a segment
    private fun calculateAverageSpeed(segment: List<Location>): Double {
        if (segment.isEmpty()) return 0.0
        var totalSpeed = 0.0
        for (i in 1 until segment.size) {
            val distance = segment[i].distanceTo(segment[i - 1])
            val time = (segment[i].time - segment[i - 1].time) / 1000.0
            totalSpeed += distance / time
        }
        return totalSpeed / (segment.size - 1)
    }

    // Display a confirmation dialog before deleting all data
    private fun deleteAllData() {
        val builder = androidx.appcompat.app.AlertDialog.Builder(this)
        builder.setTitle("Confirm Deletion")
        builder.setMessage("Are you sure you want to delete all data?")
        builder.setPositiveButton("Yes") { dialog, _ ->
            // Proceed with deletion
            performDataDeletion()
            dialog.dismiss()
        }
        builder.setNegativeButton("No") { dialog, _ ->
            dialog.dismiss()
        }
        builder.create().show()
    }

    // Perform the actual data deletion and update UI
    private fun performDataDeletion() {
        lifecycleScope.launch(Dispatchers.IO) {
            // Clear database
            db.locationDataDao().deleteAll()

            // Perform UI updates on the main thread
            launch(Dispatchers.Main) {
                // Clear all stored data
                locations.clear()
                segments.clear()
                routes.clear()

                // Clear all overlays from the map
                map.overlayManager.clear()

                // Re-add the location overlay to keep the ability to track location
                map.overlayManager.add(locationOverlay)

                // Reinitialize the completePath polyline
                completePath = Polyline().apply {
                    title = "Complete Path"
                    map.overlayManager.add(this)
                }

                // Stop location updates to prevent further tracking
                stopLocationService()

                // Reset the tracking flag and update the button text to "Start Location Updates"
                isTracking = false
                toggleButton.text = getString(R.string.start_location_updates)

                // Redraw the map without any paths
                map.invalidate()

                // Provide user feedback
                Toast.makeText(this@MainActivity, "All data deleted", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
