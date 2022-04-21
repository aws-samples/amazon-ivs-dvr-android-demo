package com.amazon.ivs.livetovod.repository.networking.models

enum class RequestError(val errorDescription: String, val code: Int) {
    METADATA_REQUEST_ERROR("Error while fetching metadata", 400),
}
