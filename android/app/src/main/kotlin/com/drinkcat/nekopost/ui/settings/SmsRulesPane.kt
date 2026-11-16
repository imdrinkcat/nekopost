package com.drinkcat.nekopost.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.drinkcat.nekopost.R
import com.drinkcat.nekopost.core.rules.RuleAction
import com.drinkcat.nekopost.core.rules.RulePatternError
import com.drinkcat.nekopost.core.rules.SmsSenderRule
import com.drinkcat.nekopost.core.rules.SmsSenderRules
import com.drinkcat.nekopost.core.ui.component.NekoCard
import com.drinkcat.nekopost.core.ui.component.StatusPill
import com.drinkcat.nekopost.core.ui.theme.NekoTextStyles
import com.drinkcat.nekopost.core.ui.theme.StatusTone
import com.drinkcat.nekopost.core.ui.token.NekoTokens

/**
 * SMS sender allow/deny list. Default: forward everything. Rules are checked
 * top to bottom and the first match decides; `*` matches anything. A "试一试"
 * field shows what would happen to a given sender.
 */
@Composable
fun SmsRulesPane(rules: List<SmsSenderRule>, onRulesChange: (List<SmsSenderRule>) -> Unit) {
    Text(stringResource(R.string.rules_intro), style = MaterialTheme.typography.bodyMedium)

    NekoCard(title = stringResource(R.string.rules_list_title)) {
        if (rules.isEmpty()) {
            Text(
                stringResource(R.string.rules_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        rules.forEachIndexed { i, rule ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            RuleRow(i + 1, rule, onDelete = { onRulesChange(rules - rule) })
        }
    }

    AddRuleCard(rules, onAdd = { onRulesChange(rules + it) })
    TryRuleCard(rules)
}

@Composable
private fun RuleRow(index: Int, rule: SmsSenderRule, onDelete: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(
            "$index",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(20.dp),
        )
        ActionPill(rule.action)
        Spacer(Modifier.width(NekoTokens.Spacing.m))
        Text(rule.pattern, style = NekoTextStyles.mono, modifier = Modifier.weight(1f))
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.rules_delete, rule.pattern))
        }
    }
}

@Composable
private fun ActionPill(action: RuleAction) {
    StatusPill(
        text = stringResource(if (action == RuleAction.Forward) R.string.rules_action_forward else R.string.rules_action_skip),
        tone = if (action == RuleAction.Forward) StatusTone.Success else StatusTone.Neutral,
    )
}

@Composable
private fun AddRuleCard(rules: List<SmsSenderRule>, onAdd: (SmsSenderRule) -> Unit) {
    var pattern by rememberSaveable { mutableStateOf("") }
    var action by rememberSaveable { mutableStateOf(RuleAction.Skip) }
    var error by rememberSaveable { mutableStateOf<RulePatternError?>(null) }
    val currentError = error
    NekoCard(title = stringResource(R.string.rules_add_title)) {
        OutlinedTextField(
            value = pattern,
            onValueChange = {
                pattern = it
                error = null
            },
            label = { Text(stringResource(R.string.rules_pattern_label)) },
            placeholder = { Text("CMBCHINA · +8190* · *广告*") },
            singleLine = true,
            isError = currentError != null,
            supportingText = if (currentError != null) {
                { Text(ruleErrorText(currentError)) }
            } else {
                null
            },
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
            textStyle = NekoTextStyles.mono.copy(fontSize = MaterialTheme.typography.bodyLarge.fontSize),
            modifier = Modifier.fillMaxWidth().testTag("rule_pattern"),
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            val options = listOf(RuleAction.Skip, RuleAction.Forward)
            options.forEachIndexed { i, a ->
                SegmentedButton(
                    selected = action == a,
                    onClick = { action = a },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                    label = {
                        Text(stringResource(if (a == RuleAction.Forward) R.string.rules_action_forward else R.string.rules_action_skip))
                    },
                )
            }
        }
        Button(
            onClick = {
                val e = SmsSenderRules.validate(pattern, rules)
                if (e != null) {
                    error = e
                } else {
                    onAdd(SmsSenderRule(pattern.trim(), action))
                    pattern = ""
                }
            },
            enabled = pattern.isNotBlank(),
            modifier = Modifier.heightIn(min = NekoTokens.Touch.minTarget).testTag("rule_add"),
        ) {
            Text(stringResource(R.string.rules_add))
        }
    }
}

@Composable
private fun TryRuleCard(rules: List<SmsSenderRule>) {
    var sender by rememberSaveable { mutableStateOf("") }
    NekoCard(title = stringResource(R.string.rules_try_title)) {
        OutlinedTextField(
            value = sender,
            onValueChange = { sender = it },
            label = { Text(stringResource(R.string.rules_try_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (sender.isNotBlank()) {
            val hit = rules.indexOfFirst { it.matches(sender) }
            val decision = SmsSenderRules.decide(rules, sender)
            Column(Modifier.padding(top = NekoTokens.Spacing.xs)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ActionPill(decision)
                    Spacer(Modifier.width(NekoTokens.Spacing.s))
                    Text(
                        if (hit >= 0) {
                            stringResource(R.string.rules_try_hit, hit + 1, rules[hit].pattern)
                        } else {
                            stringResource(R.string.rules_try_default)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ruleErrorText(e: RulePatternError): String = stringResource(
    when (e) {
        RulePatternError.Empty -> R.string.rules_error_empty
        RulePatternError.TooLong -> R.string.rules_error_too_long
        RulePatternError.InvalidCharacter -> R.string.rules_error_invalid
        RulePatternError.Duplicate -> R.string.rules_error_duplicate
        RulePatternError.TooMany -> R.string.rules_error_too_many
    },
)
