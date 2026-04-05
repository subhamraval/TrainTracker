package com.traintracker

import com.google.gson.GsonBuilder
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
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
                .addHeader(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                )
                .build()
            chain.proceed(request)
        }
        .build()

    val api: ConfirmTktApi = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(httpClient)
        .addConverterFactory(GsonConverterFactory.create(gson))
        .build()
        .create(ConfirmTktApi::class.java)
}
