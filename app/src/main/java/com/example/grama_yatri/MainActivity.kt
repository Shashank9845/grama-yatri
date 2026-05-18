package com.example.grama_yatri

import android.Manifest
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.database.*
import org.json.JSONObject
import java.io.InputStream
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private lateinit var database: DatabaseReference
    private lateinit var adapter: BusAdapter
    private lateinit var emptyStateText: TextView
    private lateinit var sourceSpinner: Spinner
    private lateinit var destSpinner: Spinner
    private lateinit var travelTimeText: TextView
    private lateinit var currentLocationText: TextView

    // All cities loaded from Firebase
    private val cityList  = mutableListOf<City>()
    // Current stops shown in RecyclerView
    private val busStops  = mutableListOf<BusStop>()
    private var currentStatus = BusStatus()
    // Key of the active route node in Firebase e.g. "belagavi_dharwad"
    private var activeRouteKey: String? = null
    private var routeListener: ValueEventListener? = null

    private var isDataLoaded = false
    private val timeoutHandler = Handler(Looper.getMainLooper())
    private val timeoutRunnable = Runnable { if (!isDataLoaded) loadMockData() }

    private val CHANNEL_ID = "bus_alerts"
    private val NOTIFICATION_PERMISSION_CODE = 101

    // Prevent spinner callbacks from firing during programmatic setup
    private var spinnerReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        createNotificationChannel()
        checkNotificationPermission()

        emptyStateText      = findViewById(R.id.emptyStateText)
        sourceSpinner       = findViewById(R.id.sourceSpinner)
        destSpinner         = findViewById(R.id.destSpinner)
        travelTimeText      = findViewById(R.id.travelTimeText)
        currentLocationText = findViewById(R.id.currentLocationText)

        val recyclerView = findViewById<RecyclerView>(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)

        adapter = BusAdapter(
            busStops,
            currentStatus,
            onPingClick   = { idx, msg  ->
                activeRouteKey?.let { updateFirebaseStatus(it, idx, msg, "NORMAL") }
                busStops.getOrNull(idx)?.let { showLocalNotification(it.name) }
            },
            onReportClick = { idx, type ->
                activeRouteKey?.let { updateFirebaseStatus(it, idx, "User Alert", type) }
            },
            onItemClick = { idx -> busStops.getOrNull(idx)?.let { showStopDetails(it) } }
        )
        recyclerView.adapter = adapter

        database = FirebaseDatabase.getInstance().reference

        // Load city list first, then wire spinners
        timeoutHandler.postDelayed(timeoutRunnable, 4000)
        fetchCities()
    }

    // ─── 1. Load city list ────────────────────────────────────────────────────

    private fun fetchCities() {
        database.child("grama_yatri/cities")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    timeoutHandler.removeCallbacks(timeoutRunnable)
                    isDataLoaded = true
                    cityList.clear()
                    snapshot.children.forEach { child ->
                        child.getValue(City::class.java)?.let { cityList.add(it) }
                    }
                    if (cityList.isEmpty()) { loadMockData(); return }
                    setupCitySpinners()
                }
                override fun onCancelled(error: DatabaseError) {
                    if (!isDataLoaded) loadMockData()
                }
            })
    }

    // ─── 2. Populate source / dest spinners ───────────────────────────────────

    private fun setupCitySpinners() {
        val names = cityList.map { it.name }
        val sa = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)

        spinnerReady = false
        sourceSpinner.adapter = sa
        destSpinner.adapter   = sa
        // Default: first city → second city
        if (cityList.size > 1) destSpinner.setSelection(1)

        val listener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (spinnerReady) loadRouteForSelection()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        sourceSpinner.onItemSelectedListener = listener
        destSpinner.onItemSelectedListener   = listener

        spinnerReady = true
        loadRouteForSelection()          // load initial route
    }

    // ─── 3. Build route key & fetch matching route ────────────────────────────

    private fun loadRouteForSelection() {
        val srcIdx  = sourceSpinner.selectedItemPosition
        val dstIdx  = destSpinner.selectedItemPosition
        if (srcIdx < 0 || dstIdx < 0 || srcIdx == dstIdx) {
            travelTimeText.text      = "Select different source and destination"
            currentLocationText.text = ""
            busStops.clear()
            adapter.updateData(busStops, BusStatus())
            updateUI()
            return
        }

        val srcId = cityList[srcIdx].id
        val dstId = cityList[dstIdx].id
        val key   = "${srcId}_${dstId}"

        if (key == activeRouteKey) return      // already listening to this route

        // Remove old listener
        detachRouteListener()
        activeRouteKey = key

        // Try direct key first; if not found, try reversed
        listenToRoute(key, fallback = "${dstId}_${srcId}")
    }

    private fun listenToRoute(key: String, fallback: String) {
        val ref = database.child("grama_yatri/routes/$key")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) {
                    // Try reversed route key
                    detachRouteListener()
                    if (key != fallback) listenToRoute(fallback, fallback)
                    else showNoRouteFound()
                    return
                }
                activeRouteKey = key   // confirm the key that actually exists

                busStops.clear()
                snapshot.child("stops").children
                    .mapNotNull { it.getValue(BusStop::class.java) }
                    .sortedBy { it.index }
                    .forEach { busStops.add(it) }

                snapshot.child("status").getValue(BusStatus::class.java)
                    ?.let { currentStatus = it }

                adapter.updateData(busStops, currentStatus)
                updateUI()
                updateInfoBar()
            }
            override fun onCancelled(error: DatabaseError) { showNoRouteFound() }
        }

        ref.addValueEventListener(listener)
        routeListener = listener
        // Store ref so we can remove it later
        database.child("grama_yatri/routes/$key").also {
            // keep ref for detach — re-assign activeRef
        }
    }

    private fun detachRouteListener() {
        routeListener?.let { listener ->
            activeRouteKey?.let { key ->
                database.child("grama_yatri/routes/$key").removeEventListener(listener)
            }
        }
        routeListener  = null
        activeRouteKey = null
    }

    private fun showNoRouteFound() {
        busStops.clear()
        currentStatus = BusStatus()
        adapter.updateData(busStops, currentStatus)
        travelTimeText.text      = "No route found for this combination"
        currentLocationText.text = ""
        updateUI()
    }

    // ─── 4. Firebase status write ─────────────────────────────────────────────

    private fun updateFirebaseStatus(routeKey: String, idx: Int, reporter: String, type: String) {
        val update = BusStatus(idx, System.currentTimeMillis(), reporter, type)
        database.child("grama_yatri/routes/$routeKey/status").setValue(update)
    }

    // ─── 5. UI helpers ────────────────────────────────────────────────────────

    private fun updateInfoBar() {
        val total = busStops.dropLast(1).sumOf { it.avgTimeToNext }
        travelTimeText.text = "Total Travel Time: $total mins"

        currentLocationText.text = "Current Bus Location: " +
                if (currentStatus.lastStopIndex >= 0)
                    busStops.getOrNull(currentStatus.lastStopIndex)?.name ?: "Unknown"
                else "Waiting for Ping"
    }

    private fun updateUI() {
        emptyStateText.visibility = if (busStops.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showStopDetails(stop: BusStop) {
        AlertDialog.Builder(this)
            .setTitle("${stop.name} Details")
            .setMessage("${Random.nextInt(1, 4)} bus(es) currently en route to this stop.")
            .setPositiveButton("OK", null)
            .show()
    }

    // ─── 6. Mock fallback (no Firebase) ──────────────────────────────────────

    private fun loadMockData() {
        try {
            val inputStream: InputStream = resources.openRawResource(R.raw.routes)
            val jsonString = inputStream.bufferedReader().use { it.readText() }
            val jsonObject = JSONObject(jsonString)

            // 1. Sync Cities from JSON
            val citiesJson = jsonObject.getJSONObject("cities")
            cityList.clear()
            citiesJson.keys().forEach { key ->
                val cityObj = citiesJson.getJSONObject(key)
                cityList.add(City(cityObj.getString("id"), cityObj.getString("name")))
            }

            // 2. Sync Routes (Load the first available route as default mock)
            val routesJson = jsonObject.getJSONObject("routes")
            val firstRouteKey = routesJson.keys().next()
            val stopsArray = routesJson.getJSONObject(firstRouteKey).getJSONArray("stops")

            busStops.clear()
            for (i in 0 until stopsArray.length()) {
                val stopObj = stopsArray.getJSONObject(i)
                busStops.add(BusStop(
                    index = stopObj.getInt("index"),
                    name = stopObj.getString("name"),
                    avgTimeToNext = stopObj.getInt("avgTimeToNext")
                ))
            }
        } catch (e: Exception) {
            // Fallback hardcoded data if JSON parsing fails
            cityList.clear()
            cityList.add(City("kitturu",  "Kitturu Village"))
            cityList.add(City("sangolli", "Sangolli Junction"))
            cityList.add(City("belagavi", "Belagavi Outskirts"))
            cityList.add(City("terminal", "City Terminal"))

            busStops.clear()
            busStops.add(BusStop(0, "Kitturu Village",    15))
            busStops.add(BusStop(1, "Sangolli Junction",  20))
            busStops.add(BusStop(2, "Belagavi Outskirts", 10))
            busStops.add(BusStop(3, "City Terminal",       0))
        }

        currentStatus = BusStatus(-1, System.currentTimeMillis(), "System", "NORMAL")
        setupCitySpinners()
        adapter.updateData(busStops, currentStatus)
        updateUI()
        updateInfoBar()
    }

    // ─── 7. Notifications ────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Bus Alerts", NotificationManager.IMPORTANCE_DEFAULT)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_CODE)
    }

    private fun showLocalNotification(stopName: String) {
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Bus Pinged at $stopName!")
            .setContentText("Route ETAs have been updated.")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true).build()

        with(NotificationManagerCompat.from(this)) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            ) notify(System.currentTimeMillis().toInt(), n)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        detachRouteListener()
        timeoutHandler.removeCallbacks(timeoutRunnable)
    }
}