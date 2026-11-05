package com.megix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

/** Defines a provider and its execution logic for Standard, Anime, and MALSync data. */
data class ProviderDef(
    val key: String,
    val displayName: String,
    val isTorrent: Boolean = false,
    val executeStandard: (suspend CineStreamExtractors.(res: AllLoadLinksData, subCb: (SubtitleFile) -> Unit, cb: (ExtractorLink) -> Unit) -> Unit)? = null,
    val executeAnime: (suspend CineStreamExtractors.(res: AllLoadLinksData, subCb: (SubtitleFile) -> Unit, cb: (ExtractorLink) -> Unit) -> Unit)? = null,
)

object ProviderRegistry {

    val builtInProviders: List<ProviderDef> = listOf(
        // Provider invoke implementations are defined in separate provider files.
        // Entries are added here when their invoke functions are implemented.
    )

    // Dynamically provided to Settings.kt
    val keys get() = builtInProviders.map { it.key }
    val namesMap get() = builtInProviders.associate { it.key to it.displayName }
    val torrentKeys get() = builtInProviders.filter { it.isTorrent }.map { it.key }.toSet()
}
