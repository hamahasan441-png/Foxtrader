package com.foxtrader.app.di

import com.foxtrader.app.data.repository.RoomExecutionAuditLog
import com.foxtrader.app.domain.usecase.execution.ExecutionAuditLog
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Provides the durable execution audit log. */
@Module
@InstallIn(SingletonComponent::class)
object ExecutionModule {

    @Provides
    @Singleton
    fun provideExecutionAuditLog(impl: RoomExecutionAuditLog): ExecutionAuditLog = impl

}
