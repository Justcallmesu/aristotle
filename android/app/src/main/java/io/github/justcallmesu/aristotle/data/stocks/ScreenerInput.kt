package io.github.justcallmesu.aristotle.data.stocks

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/** A setup of Settings › Stocks › Screener (Q86): each has its own switch. */
enum class SetupKey { Breakout, VolumeSpike, MaCross, RsiRebound, Gap }

/**
 * A number field of the screener settings with the server's range (§20.3; `every_min` 5..30 from §20.2) and the −/+ step.
 * [whole]: the server takes an integer only. [setup]: the setup whose switch shows the field (null: always shown).
 */
enum class ScreenerField(val min: Double, val max: Double, val step: Double, val whole: Boolean, val setup: SetupKey?) {
    BreakoutLookback(10.0, 60.0, 1.0, true, SetupKey.Breakout),
    BreakoutVolMult(1.0, 5.0, 0.5, false, SetupKey.Breakout),
    SpikeMult(1.5, 10.0, 0.5, false, SetupKey.VolumeSpike),
    RsiLevel(15.0, 45.0, 1.0, false, SetupKey.RsiRebound),
    GapMinPct(0.5, 10.0, 0.5, false, SetupKey.Gap),
    MinValueBn(0.0, 100.0, 1.0, false, null),
    ScanEvery(5.0, 30.0, 5.0, true, null),
}

/**
 * The screener settings as typed (screener-notes §2): the server's last answer ([base], [baseEvery] = the scan job's
 * minutes, null while unknown), each setup's switch, the field texts as typed, Gap up / down. The checks mirror the
 * server's (§20.3 ranges, integers where it wants one; at most two decimals, a little stricter), so the phone never sends
 * what would come back `400 bad_request`. A field of a setup that is off is hidden: if its text is wrong it is not
 * shown as an error and the saved value goes out instead. Pure.
 */
data class ScreenerDraft(
    val base: ScreenerSettings,
    val baseEvery: Int?,
    val on: Map<SetupKey, Boolean>,
    val texts: Map<ScreenerField, String>,
    val gapUp: Boolean,
    val gapDown: Boolean,
) {
    fun isOn(key: SetupKey): Boolean = on[key] == true

    /** A field is shown (and checked) when it has no setup or its setup is on; the interval only once the job is known. */
    fun visible(field: ScreenerField): Boolean = when {
        field == ScreenerField.ScanEvery -> baseEvery != null
        field.setup == null -> true
        else -> isOn(field.setup)
    }

    fun text(field: ScreenerField): String = texts[field].orEmpty()

    fun parse(field: ScreenerField): Parsed<Double> = parse(field, text(field))

    /** Why a shown field cannot be saved; null for a good or hidden one. */
    fun problem(field: ScreenerField): FieldProblem? = if (visible(field)) parse(field).problem else null

    val valid: Boolean get() = ScreenerField.entries.all { problem(it) == null }

    /** The object PUT sends, or null while a shown field is wrong. */
    fun settings(): ScreenerSettings? {
        if (!valid) return null
        fun v(field: ScreenerField): Double = parse(field).value ?: savedValue(field)
        return ScreenerSettings(
            minValueBn = v(ScreenerField.MinValueBn),
            setups = SetupSettings(
                breakout = BreakoutSettings(isOn(SetupKey.Breakout), v(ScreenerField.BreakoutLookback).toInt(), v(ScreenerField.BreakoutVolMult)),
                volumeSpike = VolumeSpikeSettings(isOn(SetupKey.VolumeSpike), v(ScreenerField.SpikeMult)),
                maCross = MaCrossSettings(isOn(SetupKey.MaCross)),
                rsiRebound = RsiReboundSettings(isOn(SetupKey.RsiRebound), v(ScreenerField.RsiLevel)),
                gap = GapSettings(isOn(SetupKey.Gap), v(ScreenerField.GapMinPct), gapUp, gapDown),
            ),
        )
    }

    /** The scan interval to write, or null when the job is unknown or the field is wrong. */
    fun every(): Int? = if (baseEvery == null) null else parse(ScreenerField.ScanEvery).value?.toInt()

    val settingsChanged: Boolean get() = settings()?.let { it != base } ?: false
    val everyChanged: Boolean get() = every()?.let { it != baseEvery } ?: false

    /** Save is offered: something changed and every shown field is good. */
    val canSave: Boolean get() = valid && (settingsChanged || everyChanged)

    fun withText(field: ScreenerField, text: String): ScreenerDraft = copy(texts = texts + (field to text.take(MAX_CHARS)))

    fun withOn(key: SetupKey, value: Boolean): ScreenerDraft = copy(on = on + (key to value))

    fun withGap(up: Boolean = gapUp, down: Boolean = gapDown): ScreenerDraft = copy(gapUp = up, gapDown = down)

    /** −/+ is offered while the value is inside the range and not at that end (screener-notes: disabled at the bound). */
    fun canStep(field: ScreenerField, by: Int): Boolean {
        val parsed = parse(field)
        val value = parsed.value ?: return when (parsed.problem) {
            FieldProblem.TooSmall -> by > 0
            FieldProblem.TooLarge -> by < 0
            else -> true
        }
        return if (by > 0) value < field.max else value > field.min
    }

    /**
     * One step up ([by] > 0) or down onto the field's grid (1,7 + 0,5 → 2,0), kept inside the range. A wrong text steps
     * from the nearest end (above the range, − gives the top), else from the saved value.
     */
    fun stepped(field: ScreenerField, by: Int): ScreenerDraft {
        val parsed = parse(field)
        val from = parsed.value ?: when (parsed.problem) {
            FieldProblem.TooLarge -> return withText(field, numberText(field.max))
            FieldProblem.TooSmall -> return withText(field, numberText(field.min))
            else -> savedValue(field)
        }
        val step = BigDecimal.valueOf(field.step)
        val current = BigDecimal.valueOf(from)
        val units = current.divide(step, 0, if (by > 0) RoundingMode.FLOOR else RoundingMode.CEILING)
        val next = units.add(BigDecimal(if (by > 0) 1 else -1)).multiply(step).toDouble().coerceIn(field.min, field.max)
        return withText(field, numberText(next))
    }

    /** The value saved on the PC for [field] (the interval: the job's, else its default). */
    fun savedValue(field: ScreenerField): Double = valueOf(base, baseEvery ?: DEFAULT_EVERY, field)

    companion object {
        /** The scan job's default interval (§20.3 `screener.scan`: every 10 min). */
        const val DEFAULT_EVERY = 10
        private const val MAX_CHARS = 8
        private const val MAX_DECIMALS = 2

        fun of(settings: ScreenerSettings, every: Int?): ScreenerDraft = ScreenerDraft(
            base = settings,
            baseEvery = every,
            on = mapOf(
                SetupKey.Breakout to settings.setups.breakout.on,
                SetupKey.VolumeSpike to settings.setups.volumeSpike.on,
                SetupKey.MaCross to settings.setups.maCross.on,
                SetupKey.RsiRebound to settings.setups.rsiRebound.on,
                SetupKey.Gap to settings.setups.gap.on,
            ),
            texts = ScreenerField.entries.associateWith { numberText(valueOf(settings, every ?: DEFAULT_EVERY, it)) },
            gapUp = settings.setups.gap.up,
            gapDown = settings.setups.gap.down,
        )

        /** "Defaults": the server's defaults (Q111) and the 10 min interval, against the same saved values. */
        fun defaults(base: ScreenerSettings, baseEvery: Int?): ScreenerDraft =
            of(ScreenerSettings(), baseEvery?.let { DEFAULT_EVERY }).copy(base = base, baseEvery = baseEvery)

        fun valueOf(s: ScreenerSettings, every: Int, field: ScreenerField): Double = when (field) {
            ScreenerField.BreakoutLookback -> s.setups.breakout.lookback.toDouble()
            ScreenerField.BreakoutVolMult -> s.setups.breakout.volMult
            ScreenerField.SpikeMult -> s.setups.volumeSpike.mult
            ScreenerField.RsiLevel -> s.setups.rsiRebound.level
            ScreenerField.GapMinPct -> s.setups.gap.minPct
            ScreenerField.MinValueBn -> s.minValueBn
            ScreenerField.ScanEvery -> every.toDouble()
        }

        /** The field text for a value: 1.5 -> "1,5", 20.0 -> "20" (no grouping while editing). */
        fun numberText(value: Double): String =
            BigDecimal.valueOf(value).stripTrailingZeros().let { if (it.signum() == 0) BigDecimal.ZERO else it }.toPlainString().replace('.', ',')

        /** "1,5" or "1.5" to a number in [field]'s range. */
        fun parse(field: ScreenerField, text: String): Parsed<Double> {
            val raw = text.filterNot { it.isWhitespace() }.replace(',', '.')
            if (raw.isEmpty()) return Parsed.bad(FieldProblem.Empty)
            if (!raw.all { it.isDigit() || it == '.' } || raw.count { it == '.' } > 1 || raw == ".") return Parsed.bad(FieldProblem.NotANumber)
            val number = runCatching { BigDecimal(raw) }.getOrNull() ?: return Parsed.bad(FieldProblem.NotANumber)
            val scale = number.stripTrailingZeros().scale()
            return when {
                field.whole && scale > 0 -> Parsed.bad(FieldProblem.NotWhole)
                scale > MAX_DECIMALS -> Parsed.bad(FieldProblem.TooManyDecimals)
                number < BigDecimal.valueOf(field.min) -> Parsed.bad(FieldProblem.TooSmall)
                number > BigDecimal.valueOf(field.max) -> Parsed.bad(FieldProblem.TooLarge)
                else -> Parsed.ok(number.toDouble())
            }
        }
    }
}

/** What is wrong with an Edit list sheet (§20.2 universe PUT). */
enum class UniverseProblem { BadCode, NoCodes, TooMany, BadValidTo, BadValidFrom, FromAfterTo }

/** The sheet checked: the lists as they go out, the distinct [count], codes that are not codes, and the problems. */
data class UniverseCheck(
    val lq45: List<String>,
    val idx80: List<String>,
    val count: Int,
    val badCodes: List<String>,
    val validFrom: String?,
    val validTo: String,
    val problems: Set<UniverseProblem>,
) {
    val ok: Boolean get() = problems.isEmpty()
}

/**
 * The Edit list sheet's checks, as `PUT /v1/stocks/universe` applies them (§20.2): codes upper-cased and trimmed, repeats
 * dropped, each `^[A-Z]{4}$`; 1..200 distinct codes over both lists; `valid_to` a `YYYY-MM-DD` date; `valid_from`
 * optional, a date not after `valid_to`. Codes are separated by spaces, commas or lines. Pure.
 */
object UniverseInput {
    const val MAX_CODES = 200
    private val CODE = Regex("^[A-Z]{4}$")
    private val DATE = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    private val SEPARATORS = Regex("[\\s,;]+")

    /** The codes of one field in order, repeats dropped, and the tokens that are not codes. */
    fun codes(text: String): Pair<List<String>, List<String>> {
        val tokens = text.split(SEPARATORS).map { it.trim().uppercase() }.filter { it.isNotEmpty() }.distinct()
        return tokens.filter { CODE.matches(it) } to tokens.filterNot { CODE.matches(it) }
    }

    fun date(text: String): LocalDate? = text.trim().takeIf { DATE.matches(it) }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    fun check(lq45Text: String, idx80Text: String, validFromText: String, validToText: String): UniverseCheck {
        val (lq45, badA) = codes(lq45Text)
        val (idx80, badB) = codes(idx80Text)
        val bad = (badA + badB).distinct()
        val count = (lq45 + idx80).distinct().size
        val to = date(validToText)
        val fromBlank = validFromText.isBlank()
        val from = if (fromBlank) null else date(validFromText)
        val problems = buildSet {
            if (bad.isNotEmpty()) add(UniverseProblem.BadCode)
            if (count == 0) add(UniverseProblem.NoCodes)
            if (count > MAX_CODES) add(UniverseProblem.TooMany)
            if (to == null) add(UniverseProblem.BadValidTo)
            if (!fromBlank && from == null) add(UniverseProblem.BadValidFrom)
            if (from != null && to != null && from.isAfter(to)) add(UniverseProblem.FromAfterTo)
        }
        return UniverseCheck(lq45, idx80, count, bad, from?.toString(), to?.toString().orEmpty(), problems)
    }

    /** A list as the field shows it: codes in order, one space apart. */
    fun text(codes: List<String>): String = codes.joinToString(" ")
}
