package io.github.justcallmesu.aristotle.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.justcallmesu.aristotle.R
import io.github.justcallmesu.aristotle.data.chat.SlashBlock
import io.github.justcallmesu.aristotle.data.chat.SlashCommand
import io.github.justcallmesu.aristotle.data.chat.SlashMenu
import io.github.justcallmesu.aristotle.ui.components.RunningSpinner
import io.github.justcallmesu.aristotle.ui.theme.AristotleTextStyles
import io.github.justcallmesu.aristotle.ui.theme.ButtonShape
import io.github.justcallmesu.aristotle.ui.theme.Sizes
import io.github.justcallmesu.aristotle.ui.theme.Spacing

/** The menu never covers more than this of the screen above the keyboard; then it scrolls. */
private val MENU_MAX_HEIGHT = 240.dp

/** Output lines shown before "Show all" (the tool card's rule, design.md §5). */
private const val OUTPUT_LINES = 12

private enum class MenuSection { Commands, Skills, Blocked }

private fun SlashCommand.section(): MenuSection = when {
    block != null -> MenuSection.Blocked
    skill -> MenuSection.Skills
    else -> MenuSection.Commands
}

/**
 * The `/` menu above the composer field (T15): Hermes' commands matching what is typed, with their descriptions,
 * grouped as the Desktop groups them (commands, then skills), then what the phone cannot run, each with its reason.
 */
@Composable
fun SlashMenuPanel(slash: SlashState, query: String, onPick: (SlashCommand) -> Unit, onRetry: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.s),
    ) {
        val catalog = slash.catalog
        when {
            catalog != null -> {
                val rows = remember(catalog, query) { SlashMenu.filter(catalog, query) }
                if (rows.isEmpty()) {
                    MenuLine(stringResource(R.string.slash_none))
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = MENU_MAX_HEIGHT)) {
                        rows.forEachIndexed { index, command ->
                            val section = command.section()
                            if (index == 0 || rows[index - 1].section() != section) {
                                item(key = "section-$section") { SectionLabel(section) }
                            }
                            item(key = command.name) { CommandRow(command, onPick) }
                        }
                    }
                }
            }
            slash.problem != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.slash_failed, slash.problem),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(start = Spacing.m),
                )
                TextButton(onClick = onRetry, shape = ButtonShape) { Text(stringResource(R.string.slash_retry)) }
            }
            else -> MenuLine(stringResource(R.string.slash_loading))
        }
    }
}

@Composable
private fun MenuLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.m, vertical = Spacing.m),
    )
}

@Composable
private fun SectionLabel(section: MenuSection) {
    val label = when (section) {
        MenuSection.Commands -> R.string.slash_section_commands
        MenuSection.Skills -> R.string.slash_section_skills
        MenuSection.Blocked -> R.string.slash_section_blocked
    }
    Text(
        text = stringResource(label),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = Spacing.m, end = Spacing.m, top = Spacing.s).semantics { heading() },
    )
}

/** One command: its name (mono) and Hermes' description; a blocked one says why instead of inserting. */
@Composable
private fun CommandRow(command: SlashCommand, onPick: (SlashCommand) -> Unit) {
    val block = command.block
    val pickLabel = stringResource(R.string.slash_pick)
    val tap = if (block == null) {
        Modifier.clickable(role = Role.Button, onClickLabel = pickLabel) { onPick(command) }
    } else {
        Modifier.semantics { disabled() }
    }
    Column(
        modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.minTouchTarget).then(tap).padding(horizontal = Spacing.m, vertical = Spacing.xs),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = command.name,
            style = AristotleTextStyles.codeInline,
            color = if (block == null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val detail = block?.let { blockReason(it) } ?: command.description
        if (detail.isNotBlank()) {
            Text(text = detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun blockReason(block: SlashBlock): String = stringResource(
    when (block) {
        SlashBlock.DesktopApp -> R.string.slash_block_desktop
        SlashBlock.PhoneControl -> R.string.slash_block_phone
        SlashBlock.NotOnPhone -> R.string.slash_block_not_yet
    },
)

/**
 * A command run from this phone, under the transcript (the Desktop prints a command's output into the chat as a
 * local line, `use-prompt-actions/slash.ts:320-336`): the command, then Hermes' output in a mono block, or why it did
 * not run. Only on this phone; ✕ removes it.
 */
@Composable
fun CommandRunRow(run: CommandRun, onDismiss: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = run.command,
                style = AristotleTextStyles.codeInline,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { onDismiss(run.id) }) {
                Icon(painter = painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.slash_dismiss), modifier = Modifier.size(Sizes.toolIcon))
            }
        }
        when (val status = run.status) {
            CommandStatus.Running -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                RunningSpinner()
                StatusLine(stringResource(R.string.slash_running))
            }
            is CommandStatus.Output -> if (status.text.isBlank()) StatusLine(stringResource(R.string.slash_no_output)) else OutputBlock(run.id, status.text)
            is CommandStatus.Blocked -> StatusLine(blockReason(status.block))
            is CommandStatus.Subcommands -> StatusLine(stringResource(R.string.slash_subcommands, status.allowed.joinToString(", ")))
            is CommandStatus.Failed -> Text(
                text = if (status.sent) stringResource(R.string.slash_refused, status.reason) else stringResource(R.string.slash_not_sent),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun StatusLine(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** design.md §5 code output: `surfaceContainerHighest`, mono 11.5/17, no wrap, first lines then "Show all N lines". */
@Composable
private fun OutputBlock(id: String, text: String) {
    val lines = remember(text) { text.trimEnd().lines() }
    var all by rememberSaveable(id) { mutableStateOf(false) }
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.fillMaxWidth()) {
        Column {
            SelectionContainer {
                Text(
                    text = if (all || lines.size <= OUTPUT_LINES) lines.joinToString("\n") else lines.take(OUTPUT_LINES).joinToString("\n"),
                    style = AristotleTextStyles.toolOutput,
                    color = MaterialTheme.colorScheme.onSurface,
                    softWrap = false,
                    modifier = Modifier.horizontalScroll(rememberScrollState()).padding(Spacing.m),
                )
            }
            if (!all && lines.size > OUTPUT_LINES) {
                TextButton(onClick = { all = true }, shape = ButtonShape, modifier = Modifier.padding(start = Spacing.xs)) {
                    Text(pluralStringResource(R.plurals.chat_tool_show_all, lines.size, lines.size))
                }
            }
        }
    }
}
