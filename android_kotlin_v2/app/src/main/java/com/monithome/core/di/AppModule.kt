package com.monithome.core.di

import com.monithome.data.network.socket.PcSocketClient
import com.monithome.data.network.socket.PcDiscovery
import com.monithome.data.network.yandex.YandexStationClient
import com.monithome.data.network.yandex.YandexLyricsClient
import com.monithome.data.repository_impl.PluginRepositoryImpl
import com.monithome.data.repository_impl.SettingsRepositoryImpl
import com.monithome.domain.repository.PluginRepository
import com.monithome.domain.repository.SettingsRepository
import com.monithome.domain.usecase.ObserveMediaProgressUseCase
import com.monithome.domain.usecase.SyncLyricsUseCase
import com.monithome.presentation.dashboard.DashboardViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

val appModule = module {
    single {
        okhttp3.OkHttpClient.Builder()
            .sslSocketFactory(
                com.monithome.data.network.yandex.YandexSslUtils.createSSLSocketFactory(),
                com.monithome.data.network.yandex.YandexSslUtils.trustManager
            )
            .hostnameVerifier { _, _ -> true }
            .build()
    }
    single { PcSocketClient(get()) }
    single { PcDiscovery(androidContext()) }
    single { YandexStationClient(androidContext(), get()) }
    single { YandexLyricsClient(get()) }
    single<PluginRepository> { PluginRepositoryImpl(androidContext(), get<PcSocketClient>(), get<YandexStationClient>(), get<YandexLyricsClient>(), get<SettingsRepository>()) }
    single<SettingsRepository> { SettingsRepositoryImpl(androidContext()) }
    
    factory { ObserveMediaProgressUseCase() }
    factory { SyncLyricsUseCase() }
    
    // ViewModels
    viewModel { DashboardViewModel(androidContext(), get<PcSocketClient>(), get<PcDiscovery>(), get<PluginRepository>(), get<SettingsRepository>()) }
}
