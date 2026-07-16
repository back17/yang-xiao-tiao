package com.skip.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleSelectorTest {
    @Test
    fun exactAndContainsSelectorsBehaveDifferently() {
        val exact = RuleSelector.triggerSelectors("=跳过").single().path.single()
        val contains = RuleSelector.triggerSelectors("跳过").single().path.single()
        assertTrue(RuleSelector.matches(exact, "跳过"))
        assertFalse(RuleSelector.matches(exact, "5 秒后跳过"))
        assertTrue(RuleSelector.matches(contains, "5 秒后跳过"))
    }

    @Test
    fun compoundTriggerRequiresEveryPart() {
        val queries = RuleSelector.triggerSelectors("不感兴趣&举报广告")
            .map { it.path.single() }
        val values = listOf("不感兴趣", "举报广告")
        assertTrue(queries.all { query -> values.any { RuleSelector.matches(query, it) } })
    }

    @Test
    fun leadingPipeFallsBackToVisibleSelectorText() {
        val selector = RuleSelector.triggerSelectors("| 跳过").single()
        assertTrue(selector.whole == null)
        assertTrue(RuleSelector.matches(selector.path.single(), "跳过广告"))
    }

    @Test
    fun pipeSeparatedSelectorKeepsItsHierarchy() {
        val selector = RuleSelector.actionSelector("root|dialog|close")!!
        assertTrue(selector.whole != null)
        assertTrue(selector.path.map { it.value } == listOf("root", "dialog", "close"))
    }
}
