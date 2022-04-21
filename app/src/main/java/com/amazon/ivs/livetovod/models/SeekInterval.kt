package com.amazon.ivs.livetovod.models

import com.amazon.ivs.livetovod.common.QUICKSEEK_INTERVAL

enum class SeekInterval(val time: Int) {
    FORWARD(QUICKSEEK_INTERVAL),
    BACKWARD(-QUICKSEEK_INTERVAL)
}
