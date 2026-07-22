package com.foxtrader.app.di

import com.foxtrader.app.domain.usecase.ai.AgentOrchestrator
import com.foxtrader.app.domain.usecase.ai.agents.IctAgent
import com.foxtrader.app.domain.usecase.ai.agents.MarketStructureAgent
import com.foxtrader.app.domain.usecase.ai.agents.PsychologyAgent
import com.foxtrader.app.domain.usecase.ai.agents.RiskAgent
import com.foxtrader.app.domain.usecase.ai.agents.SmartMoneyAgent
import com.foxtrader.app.domain.usecase.ai.agents.TrendAgent
import com.foxtrader.app.domain.usecase.ai.agents.VolumeAgent
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Assembles the multi-agent AI reasoning layer.
 *
 * Provides a singleton [AgentOrchestrator] with all reasoning agents
 * pre-registered. The [com.foxtrader.app.domain.usecase.ai.MasterDecisionEngine]
 * has an `@Inject` constructor and is provided automatically.
 */
@Module
@InstallIn(SingletonComponent::class)
object AiModule {

    @Provides
    @Singleton
    fun provideAgentOrchestrator(
        marketStructureAgent: MarketStructureAgent,
        trendAgent: TrendAgent,
        volumeAgent: VolumeAgent,
        smartMoneyAgent: SmartMoneyAgent,
        ictAgent: IctAgent,
        riskAgent: RiskAgent,
        psychologyAgent: PsychologyAgent,
    ): AgentOrchestrator = AgentOrchestrator().apply {
        registerAgent(marketStructureAgent)
        registerAgent(trendAgent)
        registerAgent(volumeAgent)
        registerAgent(smartMoneyAgent)
        registerAgent(ictAgent)
        registerAgent(riskAgent)
        registerAgent(psychologyAgent)
    }
}
