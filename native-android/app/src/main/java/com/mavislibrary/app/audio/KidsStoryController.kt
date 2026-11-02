package com.mavislibrary.app.audio

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class KidsStoryController(context: Context, connect: Boolean = android.os.Build.FINGERPRINT != "robolectric") : AutoCloseable {
    private var controller: MediaController? = null
    private val mutableConnected = MutableStateFlow(false)
    val connected = mutableConnected.asStateFlow()
    private val future = if (connect) MediaController.Builder(context.applicationContext,
        SessionToken(context, ComponentName(context, KidsStoryService::class.java))).buildAsync() else null
    init {
        future?.let { Futures.addCallback(it, object : FutureCallback<MediaController> {
            override fun onSuccess(result: MediaController) { controller = result; mutableConnected.value = true }
            override fun onFailure(t: Throwable) { mutableConnected.value = false }
        }, MoreExecutors.directExecutor()) }
    }
    fun command(action: String, args: Bundle = Bundle.EMPTY) {
        controller?.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args)
    }
    fun start(id: String, age: Int, voice: String) = command(KidsStoryService.START, Bundle().apply {
        putString("id", id); putInt("age", age); putString("voice", voice)
    })
    override fun close() { future?.let(MediaController::releaseFuture); controller = null; mutableConnected.value = false }
}
