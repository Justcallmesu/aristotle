package io.github.justcallmesu.aristotle.data

import java.util.Locale

/** The default device name on the pair form (D19). */
object DeviceName {
    /**
     * The phone's user-visible name (Settings.Global.DEVICE_NAME, e.g. "POCO X5 Pro 5G") when set; else
     * maker and model ("Xiaomi 22101320G"), with the maker left out when the model already starts with it.
     */
    fun of(userVisibleName: String?, manufacturer: String?, model: String?): String {
        userVisibleName?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val maker = manufacturer.orEmpty().trim().replaceFirstChar { it.titlecase(Locale.ROOT) }
        val product = model.orEmpty().trim()
        return when {
            maker.isEmpty() -> product
            product.isEmpty() -> maker
            product.startsWith(maker, ignoreCase = true) -> product
            else -> "$maker $product"
        }
    }
}
