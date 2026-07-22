package com.foxtrader.app.di

import com.foxtrader.app.domain.usecase.ai.AgentOrchestrator
import com.foxtrader.app.domain.usecase.ai.agents.IctAgent
import com.foxtrader.app.domain.usecase.ai.agents.LitAgent
import com.foxtrader.app.domain.usecase.ai.agents.MarketStructureAgent
import com.foxtrader.app.domain.usecase.ai.agents.NewsAgent
import com.foxtrader.app.domain.usecase.ai.agents.PsychologyAgent
import com.foxtrader.app.domain.usecase.ai.agents.RiskAgent
import com.foxtrader.app.domain.usecase.ai.agents.SmartMoneyAgent
import com.foxtrader.app.domain.usecase.ai.agents.StrategyAgent
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
 * Provides a singleton [AgentOrchestrator] with ALL 10 reasoning agents
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
        litAgent: LitAgent,
        riskAgent: RiskAgent,
        psychologyAgent: PsychologyAgent,
        newsAgent: NewsAgent,
        strategyAgent: StrategyAgent,
    ): AgentOrchestrator = AgentOrchestrator().apply {
        registerAgent(marketStructureAgent)
        registerAgent(trendAgent)
        registerAgent(volumeAgent)
        registerAgent(smartMoneyAgent)
        registerAgent(ictAgent)
        registerAgent(litAgent)
        registerAgent(riskAgent)
        registerAgent(psychologyAgent)
        registerAgent(newsAgent)
        registerAgent(strategyAgent)
    }
}
