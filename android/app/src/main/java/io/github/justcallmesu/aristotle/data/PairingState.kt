package io.github.justcallmesu.aristotle.data

enum class PairingState { NotPaired, Paired }

/** A device counts as paired only when it holds a non-blank credential. */
fun pairingStateOf(credential: String?): PairingState =
    if (credential.isNullOrBlank()) PairingState.NotPaired else PairingState.Paired
