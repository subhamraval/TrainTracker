package com.traintracker

data class TrackingConfig(
    val trainNo: String,
    val fromStation: String,
    val toStation: String,
    val dateOfJourney: String,
    val travelClass: String,
    val quota: String = "GN",
    val departureTime: String
)

data class AvailabilityResponse(
    val data: AvailabilityData?
)

data class AvailabilityData(
    val avlDayList: List<AvailDay>?,
    val trainName: String?,
    val trainNo: String?,
    val errorMessage: String?,
    val errorCode: Int?
)

data class AvailDay(
    val availablityDate: String,
    val availablityStatus: String,
    val currentBkgFlag: String?
)
