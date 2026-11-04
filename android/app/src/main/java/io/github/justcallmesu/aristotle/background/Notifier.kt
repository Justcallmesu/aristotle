package io.github.justcallmesu.aristotle.background

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.justcallmesu.aristotle.MainActivity
import io.github.justcallmesu.aristotle.R
import io.github.justcallmesu.aristotle.data.AlertBody
import io.github.justcallmesu.aristotle.data.AlertChannel
import io.github.justcallmesu.aristotle.data.AlertNotice
import io.github.justcallmesu.aristotle.data.ConnectionNotice
import io.github.justcallmesu.aristotle.data.Network
import io.github.justcallmesu.aristotle.data.NotifyAction
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The app's notification channels and notifications (T10). Texts come from the pure builders in `data/`. */
class Notifier(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    /** Creating again only renames them, so the names follow the phone's language (EN/ID). */
    fun createChannels() {
        manager.createNotificationChannelsCompat(AppChannel.entries.map(::channel))
    }

    /** The channel's switch as the system holds it (Settings mirrors it, TS1). */
    fun isOn(channel: AppChannel): Boolean = ChannelSwitch.isOn(canPost(), manager.getNotificationChannelCompat(channel.id)?.importance)

    /** POST_NOTIFICATIONS on Android 13+ (ContextCompat answers from the app's notification switch below 13). */
    @SuppressLint("InlinedApi") // a string constant; ContextCompat handles the older releases
    fun canPost(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** The foreground service's notification: where the link is, tap opens the app, Stop ends it. */
    fun ongoing(notice: ConnectionNotice?): Notification =
        NotificationCompat.Builder(context, AppChannel.Link.id)
            .setSmallIcon(R.drawable.ic_stat_aristotle)
            .setContentTitle(ongoingText(notice))
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp())
            .addAction(0, context.getString(R.string.link_stop), stopLink())
            .build()

    @SuppressLint("MissingPermission") // canPost() checks POST_NOTIFICATIONS
    fun updateOngoing(notice: ConnectionNotice?) {
        if (canPost()) manager.notify(ID_LINK, ongoing(notice))
    }

    @SuppressLint("MissingPermission") // canPost() checks POST_NOTIFICATIONS
    fun apply(action: NotifyAction) {
        when (action) {
            is NotifyAction.Cancel -> manager.cancel(action.tag, ID_ALERT)
            is NotifyAction.Post -> if (canPost()) manager.notify(action.notice.tag, ID_ALERT, alert(action.notice))
        }
    }

    private fun alert(notice: AlertNotice): Notification {
        val needsYou = notice.channel == AlertChannel.NeedsYou
        val channel = (if (needsYou) AppChannel.NeedsYou else AppChannel.Turns).id
        val headline = context.getString(if (needsYou) R.string.notify_needs_public else R.string.notify_turn_public)
        // The lock screen shows only this: agent text stays behind the unlock.
        val public = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_aristotle)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(headline)
            .build()
        return NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_aristotle)
            .setContentTitle(notice.title ?: context.getString(R.string.session_untitled))
            .setContentText(bodyText(notice.body))
            .setCategory(if (needsYou) NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_STATUS)
            .setPriority(if (needsYou) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setSilent(notice.repeat)
            // Opens the session's chat, with the prompt sheet up for a "needs you" notice. No action buttons: nothing is
            // ever answered from the notification or the lock screen (Q50); Android asks for the unlock before opening.
            .setContentIntent(openSession(notice))
            .apply { if (notice.promptId != null) setDeleteIntent(dismissed(notice)) }
            .build()
    }

    /** Swiping a "needs you" notification away tells [AlertDismissReceiver], so a reconnect does not bring it back. */
    private fun dismissed(notice: AlertNotice): PendingIntent = PendingIntent.getBroadcast(
        context,
        notice.tag.hashCode(),
        Intent(context, AlertDismissReceiver::class.java)
            .putExtra(AlertDismissReceiver.EXTRA_SESSION, notice.sessionId)
            .putExtra(AlertDismissReceiver.EXTRA_PROMPT, notice.promptId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun bodyText(body: AlertBody): String = when (body) {
        is AlertBody.Text -> body.text
        AlertBody.TurnFinishedNoText -> context.getString(R.string.notify_turn_no_text)
        is AlertBody.Approval -> context.getString(if (body.command) R.string.notify_needs_approval else R.string.notify_needs_approval_generic)
        is AlertBody.Clarify -> body.question?.let { context.getString(R.string.notify_needs_clarify, it) }
            ?: context.getString(R.string.notify_needs_clarify_no_text)
        AlertBody.Secret -> context.getString(R.string.notify_needs_secret)
        AlertBody.OnPc -> context.getString(R.string.notify_needs_on_pc)
    }

    private fun ongoingText(notice: ConnectionNotice?): String = when (notice) {
        is ConnectionNotice.Connected -> context.getString(
            if (notice.network == Network.Tailscale) R.string.link_connected_tailscale else R.string.link_connected_wifi,
            notice.pc,
        )
        is ConnectionNotice.Reconnecting -> context.getString(R.string.link_reconnecting)
        is ConnectionNotice.OfflineSince -> context.getString(R.string.link_offline_since, clock(notice.sinceMillis))
        is ConnectionNotice.HermesDown -> context.getString(R.string.link_hermes_down, notice.pc)
        null -> context.getString(R.string.link_reconnecting)
    }

    private fun clock(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern(TIME, Locale.getDefault()))

    /** Brings the app's task forward (or starts it), like the launcher icon. */
    private fun openApp(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return PendingIntent.getActivity(context, REQUEST_OPEN, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Brings the app forward on [AlertNotice.sessionId]'s chat ([MainActivity.openIntent]); one PendingIntent per notification tag. */
    private fun openSession(notice: AlertNotice): PendingIntent = PendingIntent.getActivity(
        context,
        notice.tag.hashCode(),
        MainActivity.openIntent(context, notice.sessionId, notice.title, showPrompt = notice.channel == AlertChannel.NeedsYou, profile = notice.profile),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun stopLink(): PendingIntent = PendingIntent.getService(
        context,
        REQUEST_STOP,
        Intent(context, LinkService::class.java).setAction(LinkService.ACTION_STOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun channel(channel: AppChannel) =
        NotificationChannelCompat.Builder(channel.id, channel.importance)
            .setName(context.getString(channel.title))
            .setDescription(context.getString(channel.description))
            .setShowBadge(channel.showBadge)
            .build()

    companion object {
        const val ID_LINK = 1
        private const val ID_ALERT = 2
        private const val REQUEST_OPEN = 1
        private const val REQUEST_STOP = 2
        private const val TIME = "HH:mm"
    }
}
