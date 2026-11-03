package me.rerere.ai.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ModelKeywordSearchTest {
    private val sonnet = Model(modelId = "claude-sonnet-5", displayName = "常用思考模型")
    private val opus = Model(modelId = "claude-opus-5", displayName = "复杂任务")
    private val models = listOf(sonnet, opus)

    @Test
    fun `keywords may match across id and name in any order`() {
        assertEquals(listOf(sonnet), models.filterByKeywords("思考 CLAUDE sonnet"))
        assertEquals(models, models.filterByKeywords("  claude   5  "))
        assertEquals(emptyList<Model>(), models.filterByKeywords("sonnet 复杂"))
    }

    @Test
    fun `empty query retains the original ordered list`() {
        assertSame(models, models.filterByKeywords("   "))
    }
}
