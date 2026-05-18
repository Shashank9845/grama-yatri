package com.example.grama_yatri

import com.google.firebase.database.IgnoreExtraProperties

@IgnoreExtraProperties
data class City(
    val id:   String = "",
    val name: String = ""
)

@IgnoreExtraProperties
data class BusStop(
    val index:         Int    = 0,
    val name:          String = "",
    val avgTimeToNext: Int    = 0
)

@IgnoreExtraProperties
data class BusStatus(
    val lastStopIndex: Int    = -1,
    val timestamp:     Long   = 0L,
    val reportedBy:    String = "Unknown",
    val alertType:     String = "NORMAL"
) {
    fun isCanceled():  Boolean = alertType == "CANCELED"
    fun isBreakdown(): Boolean = alertType == "BREAKDOWN"
}

@IgnoreExtraProperties
data class Route(
    val from:   String               = "",
    val to:     String               = "",
    val stops:  Map<String, BusStop> = emptyMap(),
    val status: BusStatus            = BusStatus()
)