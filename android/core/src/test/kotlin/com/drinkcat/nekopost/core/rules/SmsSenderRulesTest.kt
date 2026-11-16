package com.drinkcat.nekopost.core.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsSenderRulesTest {
    @Test
    fun defaultIsForward() {
        assertEquals(RuleAction.Forward, SmsSenderRules.decide(emptyList(), "+81 90-1234-5678"))
    }

    @Test
    fun firstMatchWins() {
        val rules = listOf(
            SmsSenderRule("+8190*", RuleAction.Forward),
            SmsSenderRule("*", RuleAction.Skip),
        )
        assertEquals(RuleAction.Forward, SmsSenderRules.decide(rules, "+81 90-1234-5678"))
        assertEquals(RuleAction.Skip, SmsSenderRules.decide(rules, "CMBCHINA"))
    }

    @Test
    fun matchingIsCaseInsensitiveAndIgnoresFormatting() {
        assertTrue(SmsSenderRule("cmbchina", RuleAction.Skip).matches("CMBCHINA"))
        assertTrue(SmsSenderRule("*bank*", RuleAction.Skip).matches("RakutenBank-JP"))
        assertTrue(SmsSenderRule("09012345678", RuleAction.Skip).matches("090-1234-5678"))
        assertFalse(SmsSenderRule("CMB", RuleAction.Skip).matches("CMBCHINA"))
    }

    @Test
    fun regexCharactersAreLiteral() {
        assertTrue(SmsSenderRule("A.B+C", RuleAction.Skip).matches("AB+C"))
        assertFalse(SmsSenderRule("A?C", RuleAction.Skip).matches("ABC"))
    }

    @Test
    fun validation() {
        assertEquals(RulePatternError.Empty, SmsSenderRules.validate("  "))
        assertEquals(RulePatternError.Empty, SmsSenderRules.validate("**"))
        assertEquals(RulePatternError.TooLong, SmsSenderRules.validate("1".repeat(65)))
        assertEquals(RulePatternError.InvalidCharacter, SmsSenderRules.validate("a\tb"))
        assertEquals(
            RulePatternError.Duplicate,
            SmsSenderRules.validate("cmb china", listOf(SmsSenderRule("CMBCHINA", RuleAction.Skip))),
        )
        assertNull(SmsSenderRules.validate("CMBCHINA"))
    }

    @Test
    fun encodeDecodeRoundTrip() {
        val rules = listOf(SmsSenderRule("+8190*", RuleAction.Forward), SmsSenderRule("*广告*", RuleAction.Skip))
        assertEquals(rules, SmsSenderRules.decode(SmsSenderRules.encode(rules)))
        assertEquals(emptyList<SmsSenderRule>(), SmsSenderRules.decode("garbage\nX\tfoo\n"))
    }
}
