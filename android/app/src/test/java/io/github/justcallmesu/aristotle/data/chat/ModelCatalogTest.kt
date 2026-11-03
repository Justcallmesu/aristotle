package io.github.justcallmesu.aristotle.data.chat

import io.github.justcallmesu.aristotle.data.HermesJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The `model.options` answer as the picker shows it, and the reasoning levels per model (T15). */
class ModelCatalogTest {
    // The `ModelOptionsResult` shape (apps/shared/src/gateway-contract.generated.ts:822-862).
    private val options = HermesJson.parseToJsonElement(
        """{"model":"gpt-5.1","provider":"openai-codex","providers":[
            {"slug":"openai-codex","name":"OpenAI Codex","models":["gpt-5.1","gpt-5.1-mini","gpt-5.1"],
             "capabilities":{"gpt-5.1":{"fast":true,"reasoning":true,"can_disable_reasoning":false},"gpt-5.1-mini":{"fast":false,"reasoning":true}}},
            {"slug":"custom:nvidia","name":"NVIDIA","aliases":["nvidia"],"models":["llama-x"],"unavailable_models":["llama-x"],
             "capabilities":{"llama-x":{"fast":false,"reasoning":false}},"warning":"Rate limited"},
            {"slug":"anthropic","name":"Anthropic","models":[]},
            {"slug":"llamacpp","name":"Local","models":["qwen"]},
            {"name":"no slug","models":["x"]}
        ]}""",
    )

    @Test
    fun providersAreGroupedInHermesOrderOnlyWhenTheyHaveModels() {
        val catalog = ModelCatalog.of(options)
        assertEquals(listOf("openai-codex", "custom:nvidia"), catalog.groups.map { it.slug })
        assertEquals(listOf("gpt-5.1", "gpt-5.1-mini"), catalog.groups[0].models)
        assertEquals(setOf("llama-x"), catalog.groups[1].unavailable)
        assertEquals("Rate limited", catalog.groups[1].warning)
        assertEquals("gpt-5.1" to "openai-codex", catalog.model to catalog.provider)
    }

    @Test
    fun capabilitiesAreFoundBySlugNameOrAlias() {
        val catalog = ModelCatalog.of(options)
        assertEquals(ModelCaps(reasoning = true, canDisableReasoning = false), catalog.capabilities("openai-codex", "gpt-5.1"))
        assertEquals(ModelCaps(reasoning = false, canDisableReasoning = null), catalog.capabilities("nvidia", "llama-x"))
        assertEquals(ModelCaps(reasoning = false, canDisableReasoning = null), catalog.capabilities("NVIDIA", "llama-x"))
        assertNull(catalog.capabilities("openai-codex", "unknown"))
        assertNull(catalog.capabilities(null, "gpt-5.1"))
    }

    @Test
    fun reasoningIsHiddenForAModelWithoutIt() {
        val catalog = ModelCatalog.of(options)
        assertTrue(ReasoningLevels.choices(catalog.capabilities("nvidia", "llama-x")).isEmpty())
    }

    @Test
    fun offIsOfferedOnlyWhenTheModelCanTurnThinkingOff() {
        val catalog = ModelCatalog.of(options)
        assertEquals(ReasoningLevels.LEVELS, ReasoningLevels.choices(catalog.capabilities("openai-codex", "gpt-5.1")))
        assertEquals(listOf("none") + ReasoningLevels.LEVELS, ReasoningLevels.choices(catalog.capabilities("openai-codex", "gpt-5.1-mini")))
        // Unknown (the catalog is loading or does not say): the Desktop assumes reasoning.
        assertEquals(listOf("none") + ReasoningLevels.LEVELS, ReasoningLevels.choices(null))
    }

    @Test
    fun theCurrentLevelAndItsClampAreReadFromSessionInfo() {
        assertEquals("high", ReasoningLevels.current(" High "))
        assertEquals("none", ReasoningLevels.current("none"))
        assertNull(ReasoningLevels.current(""))
        assertNull(ReasoningLevels.current("weird"))
        assertEquals("max", ReasoningLevels.clampedTo("ultra", "max"))
        assertNull(ReasoningLevels.clampedTo("high", "high"))
        assertNull(ReasoningLevels.clampedTo("high", ""))
        assertNull(ReasoningLevels.clampedTo("none", "low"))
    }

    @Test
    fun aBrokenAnswerIsAnEmptyCatalog() {
        assertTrue(ModelCatalog.of(HermesJson.parseToJsonElement("[]")).groups.isEmpty())
        assertTrue(ModelCatalog.of(null).groups.isEmpty())
    }
}
