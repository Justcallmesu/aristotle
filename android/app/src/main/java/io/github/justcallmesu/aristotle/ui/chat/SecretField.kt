package io.github.justcallmesu.aristotle.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The text of a sudo password / secret field (Q51). It lives only in the open sheet (`remember`, never
 * `rememberSaveable` or a SavedStateHandle, so it is not written into the saved instance state), and [take] empties
 * it when the value is sent or declined. [toString] never shows the text, so it cannot reach a log by accident.
 */
class SecretField {
    var text: String by mutableStateOf("")

    val isEmpty: Boolean get() = text.isEmpty()

    /** Returns the text and clears the field. */
    fun take(): String {
        val value = text
        text = ""
        return value
    }

    override fun toString(): String = "SecretField(<redacted>)"
}
