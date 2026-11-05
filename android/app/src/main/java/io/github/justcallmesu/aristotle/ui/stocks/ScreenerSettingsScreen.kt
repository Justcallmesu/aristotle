package io.github.justcallmesu.aristotle.ui.stocks

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.justcallmesu.aristotle.R
import io.github.justcallmesu.aristotle.data.stocks.FieldProblem
import io.github.justcallmesu.aristotle.data.stocks.ScreenerDraft
import io.github.justcallmesu.aristotle.data.stocks.ScreenerField
import io.github.justcallmesu.aristotle.data.stocks.SetupKey
import io.github.justcallmesu.aristotle.data.stocks.UniverseBody
import io.github.justcallmesu.aristotle.data.stocks.UniverseProblem
import io.github.justcallmesu.aristotle.ui.components.GroupedRows
import io.github.justcallmesu.aristotle.ui.sessions.QuietLine
import io.github.justcallmesu.aristotle.ui.sessions.SectionHeader
import io.github.justcallmesu.aristotle.ui.theme.AristotleTextStyles
import io.github.justcallmesu.aristotle.ui.theme.ButtonShape
import io.github.justcallmesu.aristotle.ui.theme.Spacing
import java.util.Locale

/**
 * Settings › Stocks › Screener (screener-notes §2 `ScreenerSettingsPage`, from the page's gear and Settings › Stocks):
 * each setup on/off with its thresholds in the server's ranges (§20.3), the liquidity floor, the scan interval (the
 * `screener.scan` job, §20.2), and the universe with Edit list. Save sends only valid values (never a `400`); Defaults
 * fills the server's starting values without saving. Offline everything is read-only. Leaving drops unsaved typing.
 */
@Composable
fun ScreenerSettingsScreen(viewModel: ScreenerViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.settingsVisible(true)
        onPauseOrDispose { viewModel.settingsVisible(false) }
    }
    val leave = {
        viewModel.discardDraft()
        onBack()
    }
    BackHandler(onBack = leave)
    val snackbar = remember { SnackbarHostState() }
    ScreenerMessages(state.message, snackbar, viewModel::messageShown)
    val locale = currentLocale()
    val draft = state.draft
    val editable = state.reachable && draft != null && !state.saving
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Column(modifier = Modifier.fillMaxSize()) {
                StocksTopBar(stringResource(R.string.screener_settings), stringResource(R.string.screener_settings_subline), leave, titleStyle = AristotleTextStyles.chatTitle)
                if (!state.reachable) OfflineNotice(viewModel::retry)
                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = Spacing.l)) {
                    if (draft == null) {
                        item(key = "loading") {
                            QuietLine(state.settingsProblem?.let { stocksErrorText(it) } ?: stringResource(if (state.reachable) R.string.stocks_loading else R.string.stocks_not_loaded))
                        }
                    } else {
                        item(key = "setups-header") { SectionHeader(stringResource(R.string.screener_setups)) }
                        item(key = "setups") { SetupCards(draft, editable, viewModel) }
                        item(key = "liquidity-header") { SectionHeader(stringResource(R.string.screener_liquidity)) }
                        item(key = "liquidity") {
                            Card {
                                NumberStepper(ScreenerField.MinValueBn, draft, editable, viewModel, wide = true)
                                val under = state.home?.skipped?.illiquid
                                val note = stringResource(R.string.screener_liquidity_note) +
                                    (under?.let { " " + pluralStringResource(R.plurals.screener_liquidity_under, it, it) } ?: "")
                                CardNote(note)
                            }
                        }
                        item(key = "scan-header") { SectionHeader(stringResource(R.string.screener_scan)) }
                        item(key = "scan") {
                            Card {
                                if (draft.baseEvery != null) {
                                    NumberStepper(ScreenerField.ScanEvery, draft, editable, viewModel, wide = true)
                                    CardNote(stringResource(R.string.screener_scan_note))
                                } else {
                                    CardNote(stringResource(R.string.screener_scan_unknown))
                                }
                            }
                        }
                    }
                    item(key = "universe-header") { SectionHeader(stringResource(R.string.screener_universe)) }
                    item(key = "universe") { UniverseCard(state.universe, state.reachable, locale, viewModel::openUniverse) }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.formGutter, vertical = Spacing.m),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.m),
                ) {
                    OutlinedButton(onClick = viewModel::defaults, enabled = editable, shape = ButtonShape) { Text(stringResource(R.string.screener_defaults)) }
                    Button(onClick = viewModel::save, enabled = editable && draft?.canSave == true, shape = ButtonShape, modifier = Modifier.weight(1f)) {
                        Text(stringResource(if (state.saving) R.string.stocks_saving else R.string.stocks_save))
                    }
                }
            }
            SnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = SNACK_CLEARANCE))
        }
    }
    state.universeSheet?.let { UniverseSheetView(it, state.universe, locale, viewModel) }
}

@Composable
private fun SetupCards(draft: ScreenerDraft, editable: Boolean, viewModel: ScreenerViewModel) {
    val cards = SetupKey.entries
    GroupedRows(cards.size, modifier = Modifier.padding(horizontal = Spacing.listGutter)) { index ->
        val key = cards[index]
        val (title, desc) = when (key) {
            SetupKey.Breakout -> R.string.screener_setup_breakout to R.string.screener_setup_breakout_desc
            SetupKey.VolumeSpike -> R.string.screener_setup_volume to R.string.screener_setup_volume_desc
            SetupKey.MaCross -> R.string.screener_setup_ma to R.string.screener_setup_ma_desc
            SetupKey.RsiRebound -> R.string.screener_setup_rsi to R.string.screener_setup_rsi_desc
            SetupKey.Gap -> R.string.screener_setup_gap to R.string.screener_setup_gap_desc
        }
        val titleText = stringResource(title)
        Column(modifier = Modifier.fillMaxWidth().padding(start = Spacing.l, end = Spacing.l, top = Spacing.m, bottom = CARD_BOTTOM)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(titleText, style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = draft.isOn(key),
                    onCheckedChange = { viewModel.setSetupOn(key, it) },
                    enabled = editable,
                    modifier = Modifier.semantics { contentDescription = titleText },
                )
            }
            if (draft.isOn(key)) {
                Column(modifier = Modifier.padding(top = Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                    when (key) {
                        SetupKey.Breakout -> Row(horizontalArrangement = Arrangement.spacedBy(Spacing.l)) {
                            NumberStepper(ScreenerField.BreakoutLookback, draft, editable, viewModel, modifier = Modifier.weight(1f))
                            NumberStepper(ScreenerField.BreakoutVolMult, draft, editable, viewModel, modifier = Modifier.weight(1f))
                        }
                        SetupKey.VolumeSpike -> Row { NumberStepper(ScreenerField.SpikeMult, draft, editable, viewModel, modifier = Modifier.weight(1f)); Spacer(Modifier.weight(1f)) }
                        SetupKey.MaCross -> Text(stringResource(R.string.screener_setup_ma_fixed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SetupKey.RsiRebound -> Row { NumberStepper(ScreenerField.RsiLevel, draft, editable, viewModel, modifier = Modifier.weight(1f)); Spacer(Modifier.weight(1f)) }
                        SetupKey.Gap -> {
                            Row { NumberStepper(ScreenerField.GapMinPct, draft, editable, viewModel, modifier = Modifier.weight(1f)); Spacer(Modifier.weight(1f)) }
                            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                                GapChip(stringResource(R.string.screener_gap_up), draft.gapUp, editable) { viewModel.setGap(up = it, down = draft.gapDown) }
                                GapChip(stringResource(R.string.screener_gap_down), draft.gapDown, editable) { viewModel.setGap(up = draft.gapUp, down = it) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GapChip(label: String, selected: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    FilterChip(
        selected = selected,
        onClick = { onChange(!selected) },
        enabled = enabled,
        label = { Text(label) },
        leadingIcon = if (selected) {
            { Icon(painter = painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(io.github.justcallmesu.aristotle.ui.theme.Sizes.chipIcon)) }
        } else {
            null
        },
        shape = MaterialTheme.shapes.small,
    )
}

/**
 * A number field with − / + (screener-notes `NumberStepper`): label above, number keyboard, `small` shape, the range
 * under it; a value out of range (or not a number the server takes) is an error with the range in words, and Save is off.
 */
@Composable
private fun NumberStepper(field: ScreenerField, draft: ScreenerDraft, enabled: Boolean, viewModel: ScreenerViewModel, modifier: Modifier = Modifier, wide: Boolean = false) {
    val label = stringResource(fieldLabel(field))
    val range = rangeText(field)
    val problem = draft.problem(field)
    val support = when (problem) {
        null -> range
        FieldProblem.NotWhole -> stringResource(R.string.screener_field_whole, range)
        FieldProblem.TooManyDecimals -> stringResource(R.string.screener_field_decimals, range)
        else -> stringResource(R.string.screener_field_bad, range)
    }
    Column(modifier = if (wide) modifier.fillMaxWidth() else modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = Spacing.xs))
        OutlinedTextField(
            value = draft.text(field),
            onValueChange = { viewModel.editField(field, it) },
            enabled = enabled,
            singleLine = true,
            isError = problem != null,
            textStyle = AristotleTextStyles.tabular(MaterialTheme.typography.bodyLarge).copy(textAlign = TextAlign.Center),
            keyboardOptions = KeyboardOptions(keyboardType = if (field.whole) KeyboardType.Number else KeyboardType.Decimal),
            shape = MaterialTheme.shapes.small,
            prefix = if (wide && field == ScreenerField.MinValueBn) { { Text("Rp") } } else null,
            suffix = if (wide) { { Text(stringResource(if (field == ScreenerField.MinValueBn) R.string.screener_unit_bn else R.string.screener_unit_min)) } } else null,
            leadingIcon = {
                IconButton(onClick = { viewModel.stepField(field, -1) }, enabled = enabled && draft.canStep(field, -1)) {
                    Icon(painter = painterResource(R.drawable.ic_remove), contentDescription = stringResource(R.string.screener_decrease, label))
                }
            },
            trailingIcon = {
                IconButton(onClick = { viewModel.stepField(field, 1) }, enabled = enabled && draft.canStep(field, 1)) {
                    Icon(painter = painterResource(R.drawable.ic_add), contentDescription = stringResource(R.string.screener_increase, label))
                }
            },
            supportingText = { Text(support) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun fieldLabel(field: ScreenerField): Int = when (field) {
    ScreenerField.BreakoutLookback -> R.string.screener_field_lookback
    ScreenerField.BreakoutVolMult, ScreenerField.SpikeMult -> R.string.screener_field_vol_at_least
    ScreenerField.RsiLevel -> R.string.screener_field_rebound
    ScreenerField.GapMinPct -> R.string.screener_field_min_gap
    ScreenerField.MinValueBn -> R.string.screener_field_min_value
    ScreenerField.ScanEvery -> R.string.screener_field_every
}

/** "10–60 days", "×1–×5", "15–45", "0,5–10 %", "Rp 0–100 bn, 0 = off", "5–30 min". */
@Composable
private fun rangeText(field: ScreenerField): String {
    val lo = ScreenerDraft.numberText(field.min)
    val hi = ScreenerDraft.numberText(field.max)
    return when (field) {
        ScreenerField.BreakoutLookback -> stringResource(R.string.screener_range_days, lo, hi)
        ScreenerField.BreakoutVolMult, ScreenerField.SpikeMult -> stringResource(R.string.screener_range_times, lo, hi)
        ScreenerField.RsiLevel -> stringResource(R.string.screener_range_plain, lo, hi)
        ScreenerField.GapMinPct -> stringResource(R.string.screener_range_pct, lo, hi)
        ScreenerField.MinValueBn -> stringResource(R.string.screener_range_bn, lo, hi)
        ScreenerField.ScanEvery -> stringResource(R.string.screener_range_min, lo, hi)
    }
}

/** One grouped card (`surfaceContainerLow`, outer corners). */
@Composable
private fun Card(content: @Composable () -> Unit) {
    GroupedRows(1, modifier = Modifier.padding(horizontal = Spacing.listGutter)) {
        Column(modifier = Modifier.fillMaxWidth().padding(start = Spacing.l, end = Spacing.l, top = Spacing.m, bottom = CARD_BOTTOM)) { content() }
    }
}

@Composable
private fun CardNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.xs))
}

/** Count, source and period, Edit list; an `errorContainer` banner once the list has ended (Edit list stays). */
@Composable
private fun UniverseCard(universe: UniverseBody?, reachable: Boolean, locale: Locale, onEdit: () -> Unit) {
    if (universe == null) {
        QuietLine(stringResource(if (reachable) R.string.stocks_loading else R.string.stocks_not_loaded))
        return
    }
    Card {
        if (universe.expired) {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.m)) {
                Row(modifier = Modifier.padding(Spacing.m), horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                    Icon(painter = painterResource(R.drawable.ic_error), contentDescription = null, modifier = Modifier.size(BANNER_ICON))
                    Text(stringResource(R.string.screener_universe_expired, longDate(universe.validTo, locale) ?: universe.validTo), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
            Column(modifier = Modifier.weight(1f)) {
                Text(pluralStringResource(R.plurals.stocks_count, universe.count, universe.count), style = AristotleTextStyles.stockPrice)
                val until = longDate(universe.validTo, locale) ?: universe.validTo
                Text(
                    stringResource(if (universe.source == UniverseBody.FILE) R.string.screener_universe_file else R.string.screener_universe_bundled, until),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledTonalButton(onClick = onEdit, enabled = reachable, shape = ButtonShape) { Text(stringResource(R.string.screener_edit_list)) }
        }
        if (universe.fileError != null) CardNote(stringResource(R.string.screener_universe_file_error))
    }
}

/** Offline: settings live on the PC, so nothing can change (values dim, Save and Edit list off). */
@Composable
private fun OfflineNotice(onRetry: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.listGutter, vertical = Spacing.xs).semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(modifier = Modifier.padding(start = Spacing.m, end = Spacing.xs), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
            Icon(painter = painterResource(R.drawable.ic_cloud_off), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(BANNER_ICON))
            Text(stringResource(R.string.screener_settings_offline), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f).padding(vertical = Spacing.s))
            TextButton(onClick = onRetry, shape = ButtonShape) { Text(stringResource(R.string.conn_retry_now)) }
        }
    }
}

/**
 * Edit list (§20.2 `PUT`/`DELETE /v1/stocks/universe`): the period, the LQ45 and IDX80 codes in mono fields, replaced as a
 * whole on Save; "Use bundled list" (asks first) goes back to the list the server ships.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UniverseSheetView(sheet: UniverseSheet, universe: UniverseBody?, locale: Locale, viewModel: ScreenerViewModel) {
    val check = sheet.check
    ModalBottomSheet(
        onDismissRequest = viewModel::closeUniverse,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.xxl).padding(bottom = Spacing.xxl)) {
            Text(stringResource(R.string.screener_list_title), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.screener_list_meta, pluralStringResource(R.plurals.screener_codes, check.count, check.count)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.m),
            )
            Row(modifier = Modifier.padding(top = Spacing.l), horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                DateField(stringResource(R.string.screener_valid_from), sheet.validFrom, bad = UniverseProblem.BadValidFrom in check.problems || UniverseProblem.FromAfterTo in check.problems, enabled = !sheet.saving, modifier = Modifier.weight(1f)) {
                    viewModel.editUniverse(sheet.copy(validFrom = it))
                }
                DateField(stringResource(R.string.screener_valid_to), sheet.validTo, bad = UniverseProblem.BadValidTo in check.problems, enabled = !sheet.saving, modifier = Modifier.weight(1f)) {
                    viewModel.editUniverse(sheet.copy(validTo = it))
                }
            }
            if (UniverseProblem.FromAfterTo in check.problems) SheetError(stringResource(R.string.screener_list_from_after))
            CodesField(stringResource(R.string.screener_list_lq45), sheet.lq45, enabled = !sheet.saving) { viewModel.editUniverse(sheet.copy(lq45 = it)) }
            CodesField(stringResource(R.string.screener_list_idx80), sheet.idx80, enabled = !sheet.saving) { viewModel.editUniverse(sheet.copy(idx80 = it)) }
            Text(stringResource(R.string.screener_list_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.xs))
            if (check.badCodes.isNotEmpty()) SheetError(stringResource(R.string.screener_list_bad_codes, check.badCodes.take(MAX_BAD_SHOWN).joinToString(", ")))
            if (UniverseProblem.NoCodes in check.problems) SheetError(stringResource(R.string.screener_list_none))
            if (UniverseProblem.TooMany in check.problems) SheetError(stringResource(R.string.screener_list_too_many))
            Row(modifier = Modifier.fillMaxWidth().padding(top = Spacing.xl), verticalAlignment = Alignment.CenterVertically) {
                if (universe?.source == UniverseBody.FILE) {
                    TextButton(onClick = { viewModel.askResetUniverse(true) }, enabled = !sheet.saving, shape = ButtonShape) { Text(stringResource(R.string.screener_use_bundled)) }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = viewModel::closeUniverse, shape = ButtonShape) { Text(stringResource(R.string.stocks_cancel)) }
                Spacer(Modifier.width(Spacing.s))
                Button(onClick = viewModel::saveUniverse, enabled = check.ok && !sheet.saving, shape = ButtonShape) {
                    Text(stringResource(if (sheet.saving) R.string.stocks_saving else R.string.stocks_save))
                }
            }
        }
    }
    if (sheet.confirmReset) {
        AlertDialog(
            onDismissRequest = { viewModel.askResetUniverse(false) },
            text = { Text(stringResource(R.string.screener_use_bundled_confirm)) },
            confirmButton = { TextButton(onClick = viewModel::resetUniverse, shape = ButtonShape) { Text(stringResource(R.string.screener_use_bundled)) } },
            dismissButton = { TextButton(onClick = { viewModel.askResetUniverse(false) }, shape = ButtonShape) { Text(stringResource(R.string.stocks_cancel)) } },
        )
    }
}

@Composable
private fun DateField(label: String, value: String, bad: Boolean, enabled: Boolean, modifier: Modifier = Modifier, onValue: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValue(it.take(DATE_CHARS)) },
        label = { Text(label) },
        supportingText = { Text(stringResource(if (bad) R.string.screener_list_bad_date else R.string.screener_date_help)) },
        isError = bad,
        enabled = enabled,
        singleLine = true,
        textStyle = AristotleTextStyles.tabular(MaterialTheme.typography.bodyLarge),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
        shape = MaterialTheme.shapes.small,
        modifier = modifier,
    )
}

@Composable
private fun CodesField(label: String, value: String, enabled: Boolean, onValue: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValue(it.take(CODES_CHARS)) },
        label = { Text(label) },
        enabled = enabled,
        minLines = CODE_LINES_MIN,
        maxLines = CODE_LINES_MAX,
        textStyle = AristotleTextStyles.codeInline.copy(color = MaterialTheme.colorScheme.onSurface),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii),
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.m),
    )
}

@Composable
private fun SheetError(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = Spacing.xs))
}

private val CARD_BOTTOM = 14.dp
private val BANNER_ICON = 20.dp
private val SNACK_CLEARANCE = 72.dp
private const val DATE_CHARS = 10
private const val CODES_CHARS = 2_000
private const val CODE_LINES_MIN = 3
private const val CODE_LINES_MAX = 8
private const val MAX_BAD_SHOWN = 5
