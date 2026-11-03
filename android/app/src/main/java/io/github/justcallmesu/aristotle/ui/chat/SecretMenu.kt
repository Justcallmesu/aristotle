package io.github.justcallmesu.aristotle.ui.chat

import android.content.Context
import android.util.AttributeSet
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import androidx.core.view.get
import androidx.core.view.size

/**
 * The secret field's text actions (T12 review NIT). With "show" on the field is `VISIBLE_PASSWORD`, which the platform
 * lets the user copy, cut and share; a typed password must not reach the clipboard or another app. Paste stays.
 */
object SecretMenu {
    private val BLOCKED = setOf(android.R.id.copy, android.R.id.cut, android.R.id.shareText)

    fun allows(id: Int): Boolean = id !in BLOCKED
}

/**
 * The secret field's EditText: [SecretMenu] decides its text actions, both in the selection / insertion menus and
 * for keyboard shortcuts and accessibility actions (which all end in [onTextContextMenuItem]).
 */
class SecretEditText @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : EditText(context, attrs) {
    init {
        val strip = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean = true.also { strip(menu) }
            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = true.also { strip(menu) }
            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean = false
            override fun onDestroyActionMode(mode: ActionMode) = Unit
        }
        customSelectionActionModeCallback = strip
        customInsertionActionModeCallback = strip
    }

    override fun onTextContextMenuItem(id: Int): Boolean = SecretMenu.allows(id) && super.onTextContextMenuItem(id)

    private fun strip(menu: Menu) {
        (0 until menu.size).map { menu[it].itemId }.filterNot(SecretMenu::allows).forEach(menu::removeItem)
    }
}
