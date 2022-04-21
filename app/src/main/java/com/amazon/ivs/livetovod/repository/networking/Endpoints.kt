package com.amazon.ivs.livetovod.repository.networking

import com.amazon.ivs.livetovod.repository.networking.models.MetadataResponse
import retrofit2.http.GET

interface Endpoints {

    @GET("recording-started-latest.json")
    suspend fun getMetadata(): MetadataResponse
}
