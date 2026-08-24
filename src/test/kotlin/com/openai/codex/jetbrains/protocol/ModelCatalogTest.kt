package com.openai.codex.jetbrains.protocol

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogTest {
    @Test
    fun `maps server supplied effort capabilities without model constants`() {
        val result = JsonParser.parseString(
            """{"data":[
              {"id":"future-model","model":"future-model","displayName":"Future Model","hidden":false,
               "defaultReasoningEffort":"medium","supportedReasoningEfforts":[
                 {"reasoningEffort":"low","description":"Quick"},
                 {"reasoningEffort":"medium","description":"Balanced"}],"isDefault":true},
              {"id":"hidden","hidden":true}
            ]}""",
        )
        val models = ModelCatalog.parse(result)
        assertEquals(1, models.size)
        assertEquals("future-model", models.single().id)
        assertEquals(listOf("low", "medium"), models.single().efforts.map { it.id })
        assertEquals("medium", models.single().defaultEffort)
        assertTrue(models.single().isDefault)
    }
}
