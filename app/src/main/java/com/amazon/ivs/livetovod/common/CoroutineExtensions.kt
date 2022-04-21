package com.amazon.ivs.livetovod.common

import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import timber.log.Timber

private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

@Suppress("FunctionName")
fun <T> ConsumableSharedFlow(canReplay: Boolean = false) = MutableSharedFlow<T>(
    replay = if (canReplay) 1 else 0,
    extraBufferCapacity = 1,
    onBufferOverflow = BufferOverflow.DROP_OLDEST
)

fun AppCompatActivity.launchUI(
    lifecycleState: Lifecycle.State = Lifecycle.State.STARTED,
    block: suspend CoroutineScope.() -> Unit
) = lifecycleScope.launch(
    context = CoroutineExceptionHandler { _, e ->
        Timber.d(e, "Coroutine failed: ${e.localizedMessage}")
    }
) {
    repeatOnLifecycle(state = lifecycleState, block = block)
}

fun launchIO(block: suspend CoroutineScope.() -> Unit) = ioScope.launch(
    block = block
)

fun <T> flowIO(block: suspend FlowCollector<T>.() -> Unit) = flow(block).flowOn(Dispatchers.IO)
