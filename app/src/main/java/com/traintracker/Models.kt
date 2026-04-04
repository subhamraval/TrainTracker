package com.traintracker

data class TrackingConfig(
    val trainNo: String,
    val fromStation: String,
    val toStation: String,
    val dateOfJourney: String,   // "dd-MM-yyyy"  e.g. "26-03-2026"
    val travelClass: String,     // SL, 3A, 2A, 1A, CC, 2S etc.
    val quota: String = "GN",
    val departureTime: String    // "HH:mm" — auto-fetched from schedule API
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
    val availablityDate: String,    // "26-3-2026" (no leading zero in month)
    val availablityStatus: String,  // "CURR_AVBL-0008", "AVAILABLE-0001", "REGRET" etc.
    val currentBkgFlag: String?
)
