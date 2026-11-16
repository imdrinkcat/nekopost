package com.drinkcat.nekopost.core.rules

/*
 * SMS sender allow/deny rules (design §8.4 step 2: "本地规则（发件人的允许或拒绝名单，
 * 默认全部转发）"). Plain Kotlin so the forwarder can evaluate them before an SMS
 * enters the outbox, and the UI can edit and preview them.
 */

/** What happens to an SMS whose sender matches a rule. */
enum class RuleAction { Forward, Skip }

/**
 * One rule. [pattern] is matched against the sender address after
 * [SmsSenderRules.normalize]; `*` matches any run of characters, everything
 * else is literal and case-insensitive. Examples: `CMBCHINA`, `+8190*`, `*BANK*`.
 */
data class SmsSenderRule(val pattern: String, val action: RuleAction) {
    private val regex: Regex by lazy { SmsSenderRules.toRegex(pattern) }

    fun matches(sender: String): Boolean = regex.matches(SmsSenderRules.normalize(sender))
}

enum class RulePatternError { Empty, TooLong, InvalidCharacter, Duplicate, TooMany }

object SmsSenderRules {
    const val MAX_RULES = 100
    const val MAX_PATTERN_LENGTH = 64

    /** First matching rule wins; no match means [RuleAction.Forward] (forward everything by default). */
    fun decide(rules: List<SmsSenderRule>, sender: String): RuleAction =
        rules.firstOrNull { it.matches(sender) }?.action ?: RuleAction.Forward

    /** Strips formatting characters that differ between carriers and the SMS app: spaces, dashes, dots, brackets. */
    fun normalize(address: String): String =
        address.filterNot { it.isWhitespace() || it in "-(). " }.uppercase()

    /** Validates a new pattern against the existing [rules]. */
    fun validate(pattern: String, rules: List<SmsSenderRule> = emptyList()): RulePatternError? {
        val p = pattern.trim()
        return when {
            p.isEmpty() || p.all { it == '*' } -> RulePatternError.Empty
            p.length > MAX_PATTERN_LENGTH -> RulePatternError.TooLong
            p.any { Character.isISOControl(it) } -> RulePatternError.InvalidCharacter
            rules.any { normalize(it.pattern) == normalize(p) } -> RulePatternError.Duplicate
            rules.size >= MAX_RULES -> RulePatternError.TooMany
            else -> null
        }
    }

    internal fun toRegex(pattern: String): Regex {
        val parts = normalize(pattern).split('*')
        return Regex(parts.joinToString(".*") { Regex.escape(it) })
    }

    /** Line-based storage format: `F<TAB>pattern` or `S<TAB>pattern`; patterns never contain control characters. */
    fun encode(rules: List<SmsSenderRule>): String = rules.joinToString("\n") {
        (if (it.action == RuleAction.Forward) "F" else "S") + "\t" + it.pattern
    }

    fun decode(text: String): List<SmsSenderRule> = text.lineSequence().mapNotNull { line ->
        val tab = line.indexOf('\t')
        if (tab != 1) return@mapNotNull null
        val action = when (line[0]) {
            'F' -> RuleAction.Forward
            'S' -> RuleAction.Skip
            else -> return@mapNotNull null
        }
        val pattern = line.substring(2)
        if (validate(pattern) != null) null else SmsSenderRule(pattern.trim(), action)
    }.take(MAX_RULES).toList()
}
