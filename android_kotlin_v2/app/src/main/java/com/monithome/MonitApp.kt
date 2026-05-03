package com.monithome

import android.app.Application
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import com.monithome.core.di.appModule

class MonitApp : Application() {
    override fun onCreate() {
        super.onCreate()
        
        startKoin {
            androidContext(this@MonitApp)
            modules(appModule)
        }
    }
}
