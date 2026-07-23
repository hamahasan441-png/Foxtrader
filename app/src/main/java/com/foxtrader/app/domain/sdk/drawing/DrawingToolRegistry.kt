package com.foxtrader.app.domain.sdk.drawing

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Registry for drawing tools (built-in + custom plugins).
 * Same pattern as [com.foxtrader.app.domain.sdk.indicator.IndicatorRegistry].
 */
@Singleton
class DrawingToolRegistry @Inject constructor() {
    private val tools = LinkedHashMap<String, DrawingTool>()

    fun register(tool: DrawingTool) { tools[tool.id] = tool }
    fun unregister(id: String) { tools.remove(id) }
    fun getAll(): List<DrawingTool> = tools.values.toList()
    fun get(id: String): DrawingTool? = tools[id]
    fun contains(id: String): Boolean = tools.containsKey(id)
    val size: Int get() = tools.size
}
