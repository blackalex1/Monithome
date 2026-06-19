package com.monithome

import android.app.Application
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import com.monithome.core.di.appModule

import org.koin.android.ext.android.get
import coil.ImageLoader
import coil.ImageLoaderFactory

class MonitApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        
        startKoin {
            androidContext(this@MonitApp)
            modules(appModule)
        }
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .okHttpClient(get<okhttp3.OkHttpClient>())
            .build()
    }
}
