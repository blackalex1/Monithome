package com.monithome.core.di

import com.monithome.data.network.socket.PcSocketClient
import com.monithome.data.network.socket.PcDiscovery
import com.monithome.data.network.yandex.YandexStationClient
import com.monithome.data.network.yandex.YandexLyricsClient
import com.monithome.data.repository_impl.PluginRepositoryImpl
import com.monithome.domain.repository.PluginRepository
import com.monithome.domain.usecase.ObserveMediaProgressUseCase
import com.monithome.domain.usecase.SyncLyricsUseCase
import com.monithome.presentation.dashboard.DashboardViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

val appModule = module {
    single { PcSocketClient() }
    single { PcDiscovery(androidContext()) }
    single { YandexStationClient(androidContext()) }
    single { YandexLyricsClient() }
    single<PluginRepository> { PluginRepositoryImpl(get<PcSocketClient>(), get<YandexStationClient>(), get<YandexLyricsClient>()) }
    
    factory { ObserveMediaProgressUseCase() }
    factory { SyncLyricsUseCase() }
    
    // ViewModels
    viewModel { DashboardViewModel(get<PcSocketClient>(), get<PcDiscovery>(), get<PluginRepository>()) }
}
