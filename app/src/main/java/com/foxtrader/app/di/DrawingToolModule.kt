package com.foxtrader.app.di

import com.foxtrader.app.domain.sdk.drawing.DrawingToolRegistry
import com.foxtrader.app.domain.sdk.drawing.builtin.FibonacciTool
import com.foxtrader.app.domain.sdk.drawing.builtin.HorizontalLineTool
import com.foxtrader.app.domain.sdk.drawing.builtin.RectangleTool
import com.foxtrader.app.domain.sdk.drawing.builtin.TrendLineTool
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Registers all built-in drawing tools in the [DrawingToolRegistry].
 * Plugin/marketplace drawing tools will be registered dynamically later (H4+).
 */
@Module
@InstallIn(SingletonComponent::class)
object DrawingToolModule {

    @Provides
    @Singleton
    fun provideDrawingToolRegistry(): DrawingToolRegistry = DrawingToolRegistry().apply {
        register(TrendLineTool())
        register(HorizontalLineTool())
        register(RectangleTool())
        register(FibonacciTool())
    }
}
