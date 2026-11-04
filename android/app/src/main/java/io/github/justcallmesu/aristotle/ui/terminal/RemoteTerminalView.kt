package io.github.justcallmesu.aristotle.ui.terminal

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.text.InputType
import android.view.GestureDetector
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalEmulator
import com.termux.view.TerminalRenderer
import io.github.justcallmesu.aristotle.data.terminal.KeyBytes
import io.github.justcallmesu.aristotle.data.terminal.TermSize
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Draws a [TerminalTab]'s Termux emulator with Termux's `TerminalRenderer` and turns touch and keys into bytes for it.
 * Termux's own `TerminalView` is not used: it needs a `TerminalSession`, which forks a local shell, and its keyboard
 * options are fixed (no `IME_FLAG_NO_PERSONALIZED_LEARNING`).
 *
 * Keyboard (T19 red-team M4): visible-password text input with no suggestions, no personalised learning, no
 * fullscreen/extract UI; excluded from autofill and content capture. Text is sent as typed (no composing is kept).
 */
@SuppressLint("ViewConstructor")
class RemoteTerminalView(context: Context, private val modifiers: StickyModifiers, private val onZoom: (Float) -> Unit) : View(context) {
    private var tab: TerminalTab? = null
    private var typeface: Typeface = Typeface.MONOSPACE
    private var textSizePx = 0
    private var renderer: TerminalRenderer? = null
    private var cellWidth = 0f
    private var lineSpacing = 0
    private var lineSpacingAndAscent = 0
    private var scrollRemainder = 0f
    private var backgroundColour = 0

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) importantForContentCapture = IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
    }

    /** Shows [next] (or nothing); the previous tab stops drawing here. */
    fun attach(next: TerminalTab?) {
        if (next === tab) return
        tab?.onScreenUpdated = null
        tab = next
        next?.onScreenUpdated = ::invalidate
        updateSize()
        invalidate()
    }

    fun setFont(face: Typeface, sizePx: Int) {
        if (face == typeface && sizePx == textSizePx) return
        typeface = face
        textSizePx = sizePx
        renderer = TerminalRenderer(sizePx, face)
        val paint = Paint().apply {
            this.typeface = face
            textSize = sizePx.toFloat()
        }
        // The same metrics TerminalRenderer computes, for the cell grid.
        cellWidth = paint.measureText("X")
        lineSpacing = ceil(paint.fontSpacing).toInt()
        lineSpacingAndAscent = lineSpacing + ceil(paint.ascent()).toInt()
        updateSize()
        invalidate()
    }

    fun setBackgroundColour(colour: Int) {
        backgroundColour = colour
        invalidate()
    }

    fun showKeyboard() {
        requestFocus()
        context.getSystemService(InputMethodManager::class.java)?.showSoftInput(this, 0)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = updateSize()

    private fun updateSize() {
        val current = tab ?: return
        if (width == 0 || height == 0 || renderer == null || cellWidth <= 0f || lineSpacing <= 0) return
        val cols = maxOf(MIN_CELLS, (width / cellWidth).toInt())
        val rows = maxOf(MIN_CELLS, (height - lineSpacingAndAscent) / lineSpacing)
        current.sizeChanged(TermSize(cols, rows), cellWidth.roundToInt(), lineSpacing)
    }

    override fun onDraw(canvas: Canvas) {
        val term = tab?.emulator
        val draw = renderer
        if (term == null || draw == null) {
            canvas.drawColor(backgroundColour)
            return
        }
        draw.render(term, canvas, tab?.topRow ?: 0, -1, -1, -1, -1)
    }

    // --- Keyboard ---

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = INPUT_TYPE
        outAttrs.imeOptions = IME_OPTIONS
        return TerminalInputConnection()
    }

    private inner class TerminalInputConnection : BaseInputConnection(this, true) {
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            super.commitText(text, newCursorPosition)
            sendEditable()
            return true
        }

        override fun finishComposingText(): Boolean {
            super.finishComposingText()
            sendEditable()
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            // Nothing is kept in the editable, so a delete is Backspace that many times.
            repeat(beforeLength.coerceIn(0, MAX_DELETES)) { sendKey(KeyEvent.KEYCODE_DEL, 0) }
            return true
        }

        override fun performEditorAction(actionCode: Int): Boolean {
            sendKey(KeyEvent.KEYCODE_ENTER, 0)
            return true
        }

        private fun sendEditable() {
            val editable = editable ?: return
            if (editable.isNotEmpty()) typeText(editable)
            editable.clear()
        }
    }

    /** Typed text: one character takes the sticky modifiers (Ctrl + c), longer text goes as it is. */
    private fun typeText(text: CharSequence) {
        val current = tab ?: return
        val single = text.length == Character.charCount(Character.codePointAt(text, 0))
        if (single) {
            val (ctrl, alt) = modifiers.take()
            val codePoint = Character.codePointAt(text, 0)
            current.type(if (codePoint == '\n'.code) KeyBytes.text("\n") else KeyBytes.of(codePoint, ctrl, alt))
        } else {
            current.type(KeyBytes.text(text))
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val current = tab
        if (keyCode == KeyEvent.KEYCODE_BACK || current == null) return super.onKeyDown(keyCode, event)
        var mod = 0
        if (event.isCtrlPressed || modifiers.ctrl) mod = mod or KeyHandler.KEYMOD_CTRL
        if (event.isAltPressed || modifiers.alt) mod = mod or KeyHandler.KEYMOD_ALT
        if (event.isShiftPressed) mod = mod or KeyHandler.KEYMOD_SHIFT
        if (event.isNumLockOn) mod = mod or KeyHandler.KEYMOD_NUM_LOCK
        if (sendKey(keyCode, mod)) {
            modifiers.take()
            return true
        }
        val meta = event.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK).inv()
        val codePoint = event.getUnicodeChar(meta)
        if (codePoint <= 0 || (codePoint and KeyCharacterMap.COMBINING_ACCENT) != 0) return super.onKeyDown(keyCode, event)
        modifiers.take()
        current.type(KeyBytes.of(codePoint, (mod and KeyHandler.KEYMOD_CTRL) != 0, (mod and KeyHandler.KEYMOD_ALT) != 0))
        return true
    }

    /** A special key through Termux's KeyHandler, in the emulator's cursor and keypad modes; false if it has no code. */
    fun sendKey(keyCode: Int, keyMod: Int): Boolean {
        val current = tab ?: return false
        val term: TerminalEmulator? = current.emulator
        val code = KeyHandler.getCode(keyCode, keyMod, term?.isCursorKeysApplicationMode == true, term?.isKeypadApplicationMode == true)
            ?: return false
        current.type(code.toByteArray(Charsets.UTF_8))
        return true
    }

    /** The extra-keys row: a special key, with the sticky modifiers. */
    fun extraKey(keyCode: Int) {
        val (ctrl, alt) = modifiers.take()
        var mod = 0
        if (ctrl) mod = mod or KeyHandler.KEYMOD_CTRL
        if (alt) mod = mod or KeyHandler.KEYMOD_ALT
        sendKey(keyCode, mod)
    }

    /** The extra-keys row: a character (`|`, `/`, `-`, `~`), with the sticky modifiers. */
    fun extraChar(char: Char) = typeText(char.toString())

    // --- Touch: tap shows the keyboard, drag scrolls, pinch zooms ---

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            showKeyboard()
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            scrollBy(distanceY)
            return true
        }
    })

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            onZoom(detector.scaleFactor)
            return true
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaler.onTouchEvent(event)
        if (!scaler.isInProgress) gestures.onTouchEvent(event)
        return true
    }

    /**
     * Drag by [distanceY] px. Full-screen programs (alternate screen) get arrow keys, or wheel events when they track
     * the mouse, as Termux does; the shell's screen scrolls the transcript.
     */
    private fun scrollBy(distanceY: Float) {
        val current = tab ?: return
        val term = current.emulator ?: return
        if (lineSpacing <= 0) return
        val total = scrollRemainder + distanceY
        val rows = (total / lineSpacing).toInt()
        scrollRemainder = total - rows * lineSpacing
        if (rows == 0) return
        val up = rows < 0
        repeat(kotlin.math.abs(rows)) {
            when {
                term.isMouseTrackingActive -> term.sendMouseEvent(
                    if (up) TerminalEmulator.MOUSE_WHEELUP_BUTTON else TerminalEmulator.MOUSE_WHEELDOWN_BUTTON,
                    1, 1, true,
                )
                term.isAlternateBufferActive -> sendKey(if (up) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN, 0)
                else -> current.topRow = (current.topRow + if (up) -1 else 1).coerceIn(-term.screen.activeTranscriptRows, 0)
            }
        }
        invalidate()
    }

    private companion object {
        const val MIN_CELLS = 4
        const val MAX_DELETES = 64

        const val INPUT_TYPE = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        const val IME_OPTIONS = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
    }
}
