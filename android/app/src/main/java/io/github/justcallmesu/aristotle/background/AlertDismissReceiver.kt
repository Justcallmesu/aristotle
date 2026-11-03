package io.github.justcallmesu.aristotle.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.justcallmesu.aristotle.AristotleApplication

/** The user swiped a "needs you" notification away (its deleteIntent): the router must not restore it on reconnect. */
class AlertDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val session = intent.getStringExtra(EXTRA_SESSION) ?: return
        val prompt = intent.getStringExtra(EXTRA_PROMPT) ?: return
        (context.applicationContext as? AristotleApplication)?.container?.onAlertDismissed(session, prompt)
    }

    companion object {
        const val EXTRA_SESSION = "session"
        const val EXTRA_PROMPT = "prompt"
    }
}
