package io.github.justcallmesu.aristotle.background

import android.annotation.SuppressLint
import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import io.github.justcallmesu.aristotle.data.AppLanguage
import io.github.justcallmesu.aristotle.data.AppSettings
import java.util.Locale

/**
 * The app's own language (Q60, TS1), without AppCompat: AndroidX `AppCompatDelegate.setApplicationLocales` works below
 * Android 13 only from an `AppCompatActivity` (which needs an AppCompat theme), and this app is a `ComponentActivity`
 * with a platform theme. So (developer.android.com/guide/topics/resources/app-languages, "Implement using the Android
 * framework APIs"): on Android 13+ the platform [LocaleManager] holds the choice (kept across restarts, shown and
 * changeable in the system's per-app language page via `locales_config.xml`); below 13, [AppSettings] holds it and
 * [wrap] applies it to the activity.
 */
object AppLocales {
    /** The language in force: the platform's per-app setting on 13+ (it may have been changed in system settings). */
    fun current(context: Context, settings: AppSettings): AppLanguage =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
            if (locales.isEmpty) AppLanguage.System else AppLanguage.ofLanguage(locales[0].language)
        } else {
            settings.language.value
        }

    /** Stores [choice] and applies it now; the activity is recreated in the new language. */
    fun apply(activity: Activity, settings: AppSettings, choice: AppLanguage) {
        settings.setLanguage(choice)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val tag = choice.tag
            activity.getSystemService(LocaleManager::class.java).applicationLocales =
                if (tag == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        } else {
            activity.recreate()
        }
    }

    /**
     * Below Android 13: [base] with [choice]'s locale, for `attachBaseContext`. Unchanged on 13+ or for the phone's language.
     * The app ships as one sideloaded APK with every language inside (no app bundle language splits to download).
     */
    @SuppressLint("AppBundleLocaleChanges")
    fun wrap(base: Context, choice: AppLanguage): Context {
        val tag = choice.tag
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU || tag == null) return base
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList(Locale.forLanguageTag(tag)))
        return base.createConfigurationContext(config)
    }
}
