package io.github.justcallmesu.aristotle.ui.stocks

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.justcallmesu.aristotle.R
import io.github.justcallmesu.aristotle.data.stocks.FieldProblem
import io.github.justcallmesu.aristotle.data.stocks.HoldingInput
import io.github.justcallmesu.aristotle.data.stocks.HoldingMath
import io.github.justcallmesu.aristotle.data.stocks.LevelField
import io.github.justcallmesu.aristotle.data.stocks.LevelProblem
import io.github.justcallmesu.aristotle.data.stocks.SearchResult
import io.github.justcallmesu.aristotle.data.stocks.StockChanges
import io.github.justcallmesu.aristotle.data.stocks.StockFees
import io.github.justcallmesu.aristotle.data.stocks.StockFormat
import io.github.justcallmesu.aristotle.data.stocks.WatchlistOrder
import io.github.justcallmesu.aristotle.ui.components.GroupedRows
import io.github.justcallmesu.aristotle.ui.theme.AristotleTextStyles
import io.github.justcallmesu.aristotle.ui.theme.ButtonShape
import io.github.justcallmesu.aristotle.ui.theme.FieldShape
import io.github.justcallmesu.aristotle.ui.theme.Sizes
import io.github.justcallmesu.aristotle.ui.theme.Spacing

/** Whichever stocks sheet or dialog [state] has open: code search, a watchlist row's actions, a holding, remove-confirm. */
@Composable
fun StocksSheets(state: StocksUiState, viewModel: StocksViewModel) {
    state.search?.let { CodeSearchSheet(it, state, viewModel) }
    state.watchActions?.let { WatchActionsSheet(it, state, viewModel) }
    state.holdingSheet?.let { HoldingFormSheet(it, state.fees ?: StockFees(), viewModel) }
    state.confirmRemove?.let { code ->
        AlertDialog(
            onDismissRequest = viewModel::cancelRemoveHolding,
            title = { Text(stringResource(R.string.stocks_remove_holding_title, code)) },
            text = { Text(stringResource(R.string.stocks_remove_holding_body)) },
            confirmButton = {
                TextButton(onClick = viewModel::removeHolding, shape = ButtonShape) {
                    Text(stringResource(R.string.stocks_remove), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = viewModel::cancelRemoveHolding, shape = ButtonShape) { Text(stringResource(R.string.stocks_cancel)) } },
        )
    }
}

/**
 * Add to watchlist (and the stock picker of Add holding): a FieldShape search field, matches as grouped rows (code +
 * name), + or "On watchlist" / "Held" (board "watch-add"). The PC searches its bundled IDX list first, then Yahoo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CodeSearchSheet(search: StockSearch, state: StocksUiState, viewModel: StocksViewModel) {
    ModalBottomSheet(
        onDismissRequest = viewModel::closeSearch,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(bottom = Spacing.xxl)) {
            Text(
                text = stringResource(if (search.forHolding) R.string.stocks_add_holding else R.string.stocks_add_to_watchlist),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = Spacing.xxl),
            )
            SearchField(search.query, viewModel::search, Modifier.padding(horizontal = Spacing.l, vertical = Spacing.m))
            val rows = search.results
            when {
                search.problem != null -> SheetNote(stocksErrorText(search.problem))
                search.searching && rows.isEmpty() -> SheetNote(stringResource(R.string.stocks_searching))
                search.query.isNotBlank() && rows.isEmpty() && !search.searching -> SheetNote(stringResource(R.string.stocks_search_none, search.query.trim()))
            }
            if (rows.isNotEmpty()) {
                GroupedRows(rows.size, modifier = Modifier.padding(horizontal = Spacing.l)) { index ->
                    val row = rows[index]
                    if (search.forHolding) HoldingPickRow(row, held = row.code in state.holdingCodes, onPick = viewModel::pickForHolding)
                    else WatchAddRow(row, listed = row.code in state.watchCodes, adding = search.adding == row.code, onAdd = viewModel::addToWatchlist)
                }
            }
            SheetNote(stringResource(R.string.stocks_search_help))
        }
    }
}

@Composable
private fun SheetNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.xxl, vertical = Spacing.m),
    )
}

/** The search field (FieldShape, `surfaceContainerHigh`, 2 dp `primary` ring when focused), focused on open. */
@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit, modifier: Modifier = Modifier) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    var focused by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val description = stringResource(R.string.stocks_search_hint)
    Surface(shape = FieldShape, color = colors.surfaceContainerHigh, border = if (focused) BorderStroke(FOCUS_RING, colors.primary) else null, modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.heightIn(min = Sizes.minTouchTarget).padding(start = Spacing.m), verticalAlignment = Alignment.CenterVertically) {
            Icon(painter = painterResource(R.drawable.ic_search), contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(Sizes.fieldIcon))
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Search),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = Spacing.m)
                    .focusRequester(focus)
                    .onFocusChanged { focused = it.isFocused }
                    .semantics { contentDescription = description },
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) Text(description, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant, maxLines = 1)
                        inner()
                    }
                },
            )
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQuery("") }) {
                    Icon(painter = painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.sessions_search_clear), tint = colors.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun WatchAddRow(row: SearchResult, listed: Boolean, adding: Boolean, onAdd: (String) -> Unit) {
    ResultRow(row) {
        when {
            listed -> Text(stringResource(R.string.stocks_on_watchlist), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = Spacing.m))
            adding -> Text(stringResource(R.string.stocks_adding), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = Spacing.m))
            else -> IconButton(onClick = { onAdd(row.code) }) {
                Icon(painter = painterResource(R.drawable.ic_add), contentDescription = stringResource(R.string.stocks_add_code, row.code), tint = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun HoldingPickRow(row: SearchResult, held: Boolean, onPick: (SearchResult) -> Unit) {
    Box(modifier = Modifier.clickable(role = Role.Button) { onPick(row) }) {
        ResultRow(row) {
            if (held) Text(stringResource(R.string.stocks_held), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = Spacing.m))
        }
    }
}

@Composable
private fun ResultRow(row: SearchResult, trailing: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.listRowHeight).padding(start = Spacing.l, end = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = row.code, style = AristotleTextStyles.stockCode, modifier = Modifier.widthIn(min = CODE_COLUMN))
        Text(
            text = row.name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = Spacing.s),
        )
        trailing()
    }
}

/** A watchlist row's long-press actions: Move up, Move down, Remove from watchlist (the board leaves reorder undrawn). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WatchActionsSheet(code: String, state: StocksUiState, viewModel: StocksViewModel) {
    val codes = state.watchCodes
    ModalBottomSheet(onDismissRequest = viewModel::closeWatchActions, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.xxl)) {
            Text(text = code, style = AristotleTextStyles.stockCode, modifier = Modifier.padding(horizontal = Spacing.xxl))
            Text(
                text = state.nameOf(code),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Spacing.xxl, end = Spacing.xxl, bottom = Spacing.m),
            )
            val actions = buildList<Triple<Int, Int, () -> Unit>> {
                if (WatchlistOrder.canMove(codes, code, -1)) add(Triple(R.drawable.ic_arrow_upward, R.string.stocks_move_up) { viewModel.moveInWatchlist(code, -1) })
                if (WatchlistOrder.canMove(codes, code, 1)) add(Triple(R.drawable.ic_arrow_downward, R.string.stocks_move_down) { viewModel.moveInWatchlist(code, 1) })
                add(Triple(R.drawable.ic_delete, R.string.stocks_remove_from_watchlist) { viewModel.removeFromWatchlist(code) })
            }
            GroupedRows(actions.size, modifier = Modifier.padding(horizontal = Spacing.l)) { index ->
                val (icon, label, act) = actions[index]
                ListItem(
                    modifier = Modifier.clickable(role = Role.Button, onClick = act),
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = { Icon(painter = painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    headlineContent = { Text(stringResource(label)) },
                )
            }
        }
    }
}

/**
 * Add / edit holding (board "holding-add" / "holding-edit"): the stock, Lots ("1 lot = 100 shares · N shares"),
 * Average price (Rp), a preview of P/L after fees, Cancel / Save (disabled while invalid), Remove (edit only, `error`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HoldingFormSheet(sheet: HoldingSheet, fees: StockFees, viewModel: StocksViewModel) {
    val lots = HoldingInput.lots(sheet.lots)
    val avg = HoldingInput.avgPrice(sheet.avgPrice)
    val levels = HoldingInput.levels(sheet.stop, sheet.target, avg.value)
    val valid = lots.value != null && avg.value != null && levels.valid
    ModalBottomSheet(
        onDismissRequest = viewModel::closeHoldingSheet,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(start = Spacing.xxl, end = Spacing.xxl, bottom = Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            Text(stringResource(if (sheet.editing) R.string.stocks_edit_holding else R.string.stocks_add_holding), style = MaterialTheme.typography.headlineSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(sheet.code, style = AristotleTextStyles.stockCode)
                Text(
                    text = sheet.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = Spacing.s),
                )
            }
            NumberField(
                value = sheet.lots,
                onValue = viewModel::editLots,
                label = stringResource(R.string.stocks_lots),
                support = lots.problem?.let { lotsProblem(it) }
                    ?: lots.value?.let { stringResource(R.string.stocks_lots_support, StockFormat.number(it * HoldingMath.LOT_SIZE)) }
                    ?: stringResource(R.string.stocks_lots_support_plain),
                error = lots.problem != null && sheet.lots.isNotEmpty(),
                decimal = false,
            )
            NumberField(
                value = sheet.avgPrice,
                onValue = viewModel::editAvgPrice,
                label = stringResource(R.string.stocks_avg_price),
                support = avg.problem?.takeIf { sheet.avgPrice.isNotEmpty() }?.let { priceProblem(it) } ?: stringResource(R.string.stocks_avg_price_support),
                error = avg.problem != null && sheet.avgPrice.isNotEmpty(),
                decimal = true,
                prefix = "Rp",
            )
            // Q104 (TBA1/TBA2): optional levels for the price alerts, checked as the server does (tick grid, stop below the
            // average and the target).
            NumberField(
                value = sheet.stop,
                onValue = viewModel::editStop,
                label = stringResource(R.string.stocks_stop_optional),
                support = levels.stop.problem?.takeIf { sheet.stop.isNotBlank() }?.let { levelProblem(levels.stop, avg.value) }
                    ?: stringResource(R.string.stocks_stop_support),
                error = levels.stop.problem != null && sheet.stop.isNotBlank(),
                decimal = false,
                prefix = "Rp",
            )
            NumberField(
                value = sheet.target,
                onValue = viewModel::editTarget,
                label = stringResource(R.string.stocks_target_optional),
                support = levels.target.problem?.takeIf { sheet.target.isNotBlank() }?.let { levelProblem(levels.target, avg.value) }
                    ?: stringResource(R.string.stocks_target_support),
                error = levels.target.problem != null && sheet.target.isNotBlank(),
                decimal = false,
                prefix = "Rp",
            )
            if (lots.value != null && avg.value != null) PreviewBlock(HoldingMath.preview(lots.value!!, avg.value!!, sheet.price, fees), sheet.price, fees)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (sheet.editing) {
                    TextButton(onClick = { viewModel.askRemoveHolding(sheet.code) }, shape = ButtonShape) {
                        Text(stringResource(R.string.stocks_remove), color = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = viewModel::closeHoldingSheet, shape = ButtonShape) { Text(stringResource(R.string.stocks_cancel)) }
                Spacer(Modifier.width(Spacing.s))
                Button(onClick = viewModel::saveHolding, enabled = valid && !sheet.saving, shape = ButtonShape) {
                    Text(stringResource(if (sheet.saving) R.string.stocks_saving else R.string.stocks_save))
                }
            }
        }
    }
}

@Composable
private fun NumberField(value: String, onValue: (String) -> Unit, label: String, support: String, error: Boolean, decimal: Boolean, prefix: String? = null) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        supportingText = { Text(support) },
        isError = error,
        singleLine = true,
        prefix = prefix?.let { { Text("$it ") } },
        textStyle = AristotleTextStyles.tabular(MaterialTheme.typography.bodyLarge),
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number, imeAction = ImeAction.Next),
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun lotsProblem(problem: FieldProblem): String = stringResource(
    when (problem) {
        FieldProblem.Empty -> R.string.stocks_lots_support_plain
        FieldProblem.NotANumber, FieldProblem.NotWhole, FieldProblem.TooManyDecimals -> R.string.stocks_lots_whole
        FieldProblem.TooSmall -> R.string.stocks_lots_min
        FieldProblem.TooLarge -> R.string.stocks_lots_max
    },
)

/** Why a typed stop or target is refused; an off-tick price gets the two nearest prices it could be. */
@Composable
private fun levelProblem(field: LevelField, avg: Double?): String = when (field.problem) {
    LevelProblem.OffTick -> {
        val (below, above) = field.near ?: (0L to 0L)
        stringResource(R.string.stocks_level_tick, StockFormat.number(field.tick ?: 1), StockFormat.number(below), StockFormat.number(above))
    }
    LevelProblem.NotBelowAverage -> stringResource(R.string.stocks_level_stop_avg, avg?.let(StockFormat::price) ?: StockFormat.missing)
    LevelProblem.NotBelowTarget -> stringResource(R.string.stocks_level_stop_target)
    LevelProblem.NotANumber, LevelProblem.NotWhole -> stringResource(R.string.stocks_level_whole)
    LevelProblem.TooSmall -> stringResource(R.string.stocks_price_min)
    LevelProblem.TooLarge -> stringResource(R.string.stocks_price_max)
    null -> ""
}

@Composable
private fun priceProblem(problem: FieldProblem): String = stringResource(
    when (problem) {
        FieldProblem.Empty -> R.string.stocks_avg_price_support
        FieldProblem.NotANumber, FieldProblem.NotWhole -> R.string.stocks_price_number
        FieldProblem.TooManyDecimals -> R.string.stocks_price_decimals
        FieldProblem.TooSmall -> R.string.stocks_price_min
        FieldProblem.TooLarge -> R.string.stocks_price_max
    },
)

/**
 * The edit preview: cost with the buy fee, selling now less the sell fee, P/L after fees. The block is
 * `surfaceContainerLowest` inside the `surfaceContainerLow` sheet, so the stock palette keeps Q91's ≥ 5.4:1: light gain /
 * loss / flat 6.44 / 6.42 / 6.44:1, dark ≥ 10.4:1 (`surfaceContainer` gave 5.12 / 5.11 light). Not `surface`: in a
 * tonally elevated sheet M3 may tint a `surface`-coloured block.
 */
@Composable
private fun PreviewBlock(preview: HoldingMath.Preview, price: Long?, fees: StockFees) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLowest, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            PreviewLine(stringResource(R.string.stocks_preview_cost, StockFormat.fee(fees.buyPct)), StockFormat.rupiah(preview.cost))
            if (price != null && preview.value != null) {
                PreviewLine(stringResource(R.string.stocks_preview_sold, StockFormat.number(price), StockFormat.fee(fees.sellPct)), StockFormat.rupiah(preview.value))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.stocks_pl_after_fees), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    ChangeText(StockChanges.profit(preview.pl, preview.plPct), style = MaterialTheme.typography.labelLarge.let(AristotleTextStyles::tabular), arrow = 20.dp)
                }
            } else {
                Text(stringResource(R.string.stocks_preview_no_price), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PreviewLine(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = AristotleTextStyles.tabular(MaterialTheme.typography.labelLarge))
    }
}

private val FOCUS_RING = 2.dp
private val CODE_COLUMN = 56.dp
