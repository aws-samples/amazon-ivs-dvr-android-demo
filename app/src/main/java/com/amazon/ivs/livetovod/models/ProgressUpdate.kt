package com.amazon.ivs.livetovod.models

data class ProgressUpdate(
    val progress: Long,
    val vodTime: Long? = null
)
