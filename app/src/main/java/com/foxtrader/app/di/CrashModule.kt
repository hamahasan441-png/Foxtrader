package com.foxtrader.app.di

import com.foxtrader.app.data.crash.CompositeCrashReporter
import com.foxtrader.app.data.crash.CrashReporter
import com.foxtrader.app.data.crash.NoOpCrashBackend
import com.foxtrader.app.data.crash.RemoteCrashBackend
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the crash-reporting seam to the [CompositeCrashReporter] which dispatches
 * to both the local file-based reporter and the remote backend. The remote backend
 * defaults to [NoOpCrashBackend] until a real SDK (Sentry/Crashlytics) is added.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CrashModule {

    @Binds
    @Singleton
    abstract fun bindCrashReporter(impl: CompositeCrashReporter): CrashReporter

    @Binds
    @Singleton
    abstract fun bindRemoteCrashBackend(impl: NoOpCrashBackend): RemoteCrashBackend
}
