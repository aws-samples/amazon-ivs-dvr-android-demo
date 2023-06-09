package com.amazon.ivs.livetovod.repository

import com.amazon.ivs.livetovod.common.flowIO
import com.amazon.ivs.livetovod.repository.networking.NetworkClient
import com.amazon.ivs.livetovod.repository.networking.Response
import com.amazon.ivs.livetovod.repository.networking.models.MetadataResponse
import com.amazon.ivs.livetovod.repository.networking.models.RequestError
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

const val METADATA_REQUEST_DELAY = 5 * 1000L

@Singleton
class Repository @Inject constructor(private val networkClient: NetworkClient) {

    fun getMetadata(): Flow<Response> = flowIO {
        while (true) {
            Timber.d("Requesting metadata")
            val metadata = requestMetadata()
            if (metadata != null) {
                Timber.d("Metadata received")
                emit(Response.success(metadata))
                break
            } else {
                Timber.d("Metadata request error")
                emit(Response.error(RequestError.METADATA_REQUEST_ERROR))
            }
            delay(METADATA_REQUEST_DELAY)
        }
    }

    private suspend fun requestMetadata(): MetadataResponse? {
        return try {
            networkClient.api.getMetadata()
        } catch (e: Exception) {
            null
        }
    }
}
