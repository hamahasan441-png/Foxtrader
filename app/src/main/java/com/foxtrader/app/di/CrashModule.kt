package com.foxtrader.app.di

import com.foxtrader.app.data.crash.CrashReporter
import com.foxtrader.app.data.crash.LocalCrashReporter
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the crash-reporting seam to the offline, privacy-preserving local
 * implementation. Swap the binding here to introduce an opt-in remote backend.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CrashModule {

    @Binds
    @Singleton
    abstract fun bindCrashReporter(impl: LocalCrashReporter): CrashReporter
}
