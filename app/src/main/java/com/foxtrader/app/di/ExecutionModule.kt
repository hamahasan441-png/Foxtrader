package com.foxtrader.app.di

import com.foxtrader.app.data.repository.RoomExecutionAuditLog
import com.foxtrader.app.domain.usecase.execution.ExecutionAuditLog
import com.foxtrader.app.domain.usecase.execution.ExecutionCoordinator
import com.foxtrader.app.domain.usecase.execution.ExecutionSafetyLayer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Wires the live-execution safety stack:
 *  - the Room-backed append-only audit log
 *  - the fail-closed safety layer
 *  - the coordinator that orchestrates an order through the safety layer and
 *    transport, enforcing idempotency and duplicate-order blocking.
 *
 * The broker transport ([com.foxtrader.app.data.remote.api.MetaApiTradeTransport])
 * is bound by its own `@Inject constructor`, so no provider is needed here.
 */
@Module
@InstallIn(SingletonComponent::class)
object ExecutionModule {

    @Provides
    @Singleton
    fun provideExecutionAuditLog(impl: RoomExecutionAuditLog): ExecutionAuditLog = impl

    @Provides
    @Singleton
    fun provideExecutionSafetyLayer(): ExecutionSafetyLayer = ExecutionSafetyLayer()

    @Provides
    @Singleton
    fun provideExecutionCoordinator(
        safetyLayer: ExecutionSafetyLayer,
        auditLog: ExecutionAuditLog,
    ): ExecutionCoordinator = ExecutionCoordinator(safetyLayer, auditLog)
}
