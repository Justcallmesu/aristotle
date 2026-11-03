package io.github.justcallmesu.aristotle.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T12b NIT: with "show" on, the secret field offers no copy, cut or share (paste stays). */
class SecretMenuTest {
    @Test
    fun copyCutAndShareAreBlocked() {
        assertFalse(SecretMenu.allows(android.R.id.copy))
        assertFalse(SecretMenu.allows(android.R.id.cut))
        assertFalse(SecretMenu.allows(android.R.id.shareText))
        assertTrue(SecretMenu.allows(android.R.id.paste))
        assertTrue(SecretMenu.allows(android.R.id.selectAll))
    }
}
