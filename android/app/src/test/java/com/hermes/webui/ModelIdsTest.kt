package com.hermes.webui

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelIdsTest {
    @Test
    fun keepsDottedGrok() {
        assertEquals("grok-4.6", ModelIds.forSend("grok-4.6", "xai-oauth"))
        assertEquals("grok-4.20", ModelIds.forSend("grok-4.20", "x-ai"))
    }

    @Test
    fun repairsHyphenatedGrokVersion() {
        assertEquals("grok-4.6", ModelIds.forSend("grok-4-6", "xai-oauth"))
        assertEquals("grok-4.20", ModelIds.forSend("grok-4-20", "xai"))
        assertEquals("x-ai/grok-4.6", ModelIds.forSend("x-ai/grok-4-6", "openrouter"))
    }

    @Test
    fun grokFamilyWithoutProviderStillRepairs() {
        assertEquals("grok-4.6", ModelIds.forSend("grok-4-6", ""))
    }

    @Test
    fun leavesNonVersionHyphens() {
        assertEquals("grok-4-fast", ModelIds.forSend("grok-4-fast", "xai-oauth"))
        assertEquals("grok-code-fast-1", ModelIds.forSend("grok-code-fast-1", "xai-oauth"))
    }

    @Test
    fun doesNotTouchClaude() {
        assertEquals("claude-sonnet-4-6", ModelIds.forSend("claude-sonnet-4-6", "anthropic"))
        assertEquals("claude-sonnet-4.6", ModelIds.forSend("claude-sonnet-4.6", "anthropic"))
    }
}
