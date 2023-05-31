package com.amazon.ivs.livetovod.repository.networking

import com.amazon.ivs.livetovod.BuildConfig
import com.amazon.ivs.livetovod.repository.networking.models.RequestError
import com.amazon.ivs.livetovod.repository.networking.models.RequestStatus
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val REQUEST_TIMEOUT = 30L

@Singleton
class NetworkClient @Inject constructor() {

    private val okHttpClient by lazy {
        val builder = OkHttpClient.Builder()
            .connectTimeout(REQUEST_TIMEOUT, TimeUnit.SECONDS)
            .readTimeout(REQUEST_TIMEOUT, TimeUnit.SECONDS)
            .writeTimeout(REQUEST_TIMEOUT, TimeUnit.SECONDS)
        if (BuildConfig.DEBUG) {
            val interceptor = HttpLoggingInterceptor()
            interceptor.level = HttpLoggingInterceptor.Level.BODY
            builder.addInterceptor(interceptor)
        }
        builder.build()
    }

    private fun getRetrofit(): Retrofit {
        return Retrofit.Builder()
            .client(okHttpClient)
            .baseUrl(BuildConfig.STREAM_VOD_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    val api: Endpoints = getRetrofit().create(Endpoints::class.java)
}

data class Response(val status: RequestStatus, val error: RequestError?, val data: Any?) {
    companion object {
        fun success(data: Any) = Response(status = RequestStatus.SUCCESS, error = null, data = data)
        fun error(error: RequestError) = Response(status = RequestStatus.ERROR, error = error, data = null)
    }
}
