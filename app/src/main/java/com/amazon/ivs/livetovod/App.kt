package com.amazon.ivs.livetovod

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.amazon.ivs.livetovod.common.LineNumberDebugTree
import timber.log.Timber

class App : Application(), ViewModelStoreOwner {

    private val appViewModelStore: ViewModelStore by lazy {
        ViewModelStore()
    }

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(LineNumberDebugTree("LiveToVod"))
        }
    }

    override fun getViewModelStore() = appViewModelStore
}
