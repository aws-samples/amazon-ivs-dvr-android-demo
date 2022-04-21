package com.amazon.ivs.livetovod.repository.networking.models

import kotlinx.serialization.Serializable

@Serializable
data class MetadataResponse(
    val isChannelLive: Boolean,
    val livePlaybackUrl: String,
    val playlistDuration: Int,
    val masterKey: String,
    val recordingStartedAt: String
)
