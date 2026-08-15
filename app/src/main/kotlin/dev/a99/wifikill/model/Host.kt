package dev.a99.wifikill.model

import dev.a99.wifikill.RootExecutor.PersistentProcess

data class Host(
    val ip: String,
    val mac: String,
    val hostname: String? = null,
    val manufacturer: String? = null,
    val isKilled: Boolean = false,
    val spooferProcess: PersistentProcess? = null,
)