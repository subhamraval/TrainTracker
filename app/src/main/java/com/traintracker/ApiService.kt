package com.traintracker

import com.google.gson.GsonBuilder
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import java.util.UUID
import java.util.concurrent.TimeUnit

interface ConfirmTktApi {

    @POST("api/v1/availability/fetchAvailability")
    suspend fun fetchRaw(
        @Query("trainNo") trainNo: String,
        @Query("travelClass") travelClass: String,
        @Query("quota") quota: String,
        @Query("sourceStationCode") sourceStationCode: String,
        @Query("destinationStationCode") destinationStationCode: String,
        @Query("dateOfJourney") dateOfJourney: String,
        @Query("enableTG") enableTG: Boolean = true,
        @Query("tGPlan") tGPlan: String = "CTG-A36",
        @Query("showTGPrediction") showTGPrediction: Boolean = false,
        @Query("tgColor") tgColor: String = "DEFAULT",
        @Query("showPredictionGlobal") showPredictionGlobal: Boolean = true,
        @Query("showNewMealOptions") showNewMealOptions: Boolean = true,
        @Query("showNewAlternates") showNewAlternates: Boolean = false,
        @Query("showNewAltText") showNewAltText: Boolean = true
    ): ResponseBody

    // Trains list API — From+To+Date se sab trains milti hain with departure time
    @GET("api/v1/trains/getTrains")
    suspend fun getTrainsList(
        @Query("sourceStationCode") sourceStationCode: String,
        @Query("destinationStationCode") destinationStationCode: String,
        @Query("dateOfJourney") dateOfJourney: String
    ): ResponseBody
}

object ApiClient {
    private const val BASE_URL = "https://cttrainsapi.confirmtkt.com/"
    private val DEVICE_ID: String = UUID.randomUUID().toString()

    val gson = GsonBuilder().setLenient().create()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .addHeader("Apikey", "ct-web!2\$")
                .addHeader("Clientid", "ct-web")
                .addHeader("Deviceid", DEVICE_ID)
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "*/*")
                .addHeader("Accept-Language", "en-US,en;q=0.9")
                .addHeader("Origin", "https://www.confirmtkt.com")
                .addHeader("Referer", "https://www.confirmtkt.com/")
                .addHeader("Sec-Fetch-Dest", "empty")
                .addHeader("Sec-Fetch-Mode", "cors")
                .addHeader("Sec-Fetch-Site", "same-site")
                .addHeader("User-Agent",
                    "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                .build()
            chain.proceed(request)
        }.build()

    val api: ConfirmTktApi = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(httpClient)
        .addConverterFactory(GsonConverterFactory.create(gson))
        .build()
        .create(ConfirmTktApi::class.java)

    suspend fun getDepartureTime(
        trainNo: String,
        fromStation: String,
        toStation: String,
        dateOfJourney: String
    ): Pair<String?, String> {
        val debug = StringBuilder()
        try {
            val raw = api.getTrainsList(
                sourceStationCode      = fromStation,
                destinationStationCode = toStation,
                dateOfJourney          = dateOfJourney
            ).string()

            debug.appendLine("Raw (200): ${raw.take(200)}")

            // Parse JSON — array of trains expected
            val el = gson.fromJson(raw, com.google.gson.JsonElement::class.java)

            // Try different response structures
            val trainsArray = when {
                el.isJsonArray -> el.asJsonArray
                el.isJsonObject -> {
                    val obj = el.asJsonObject
                    obj.getAsJsonArray("data")
                        ?: obj.getAsJsonArray("trains")
                        ?: obj.getAsJsonArray("trainsList")
                        ?: obj.getAsJsonArray("body")
                }
                else -> null
            }

            if (trainsArray == null) {
                debug.appendLine("No array found in response")
                return Pair(null, debug.toString())
            }

            debug.appendLine("Trains found: ${trainsArray.size()}")

            // Find our train by number
            for (item in trainsArray) {
                if (!item.isJsonObject) continue
                val obj = item.asJsonObject

                val tNo = obj.get("trainNo")?.asString
                    ?: obj.get("trainNumber")?.asString
                    ?: obj.get("number")?.asString
                    ?: continue

                if (tNo.trim() == trainNo.trim()) {
                    // Departure time fields try karo
                    val depTime = obj.get("departureTime")?.asString
                        ?: obj.get("fromStnDepartureTime")?.asString
                        ?: obj.get("depTime")?.asString
                        ?: obj.get("departure")?.asString
                        ?: obj.get("deptTime")?.asString

                    debug.appendLine("Train $trainNo found! depTime: $depTime")
                    debug.appendLine("Full train obj: ${obj.toString().take(300)}")

                    if (!depTime.isNullOrEmpty() && depTime != "--") {
                        return Pair(depTime, debug.toString())
                    }
                }
            }

            // Train nahi mila — pehla train ka raw dikhao debug ke liye
            if (trainsArray.size() > 0) {
                debug.appendLine("Train $trainNo not found. First train sample:")
                debug.appendLine(trainsArray[0].toString().take(300))
            }

        } catch (e: Exception) {
            debug.appendLine("Error: ${e.message}")
        }

        return Pair(null, debug.toString())
    }
}
