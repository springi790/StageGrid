package dev.stagegrid.metadata

import dev.stagegrid.debug.StageGridDebugLog

/**
 * Tries metadata catalogs in priority order and stops at the first provider that returns
 * candidates. Individual HTTP failures are isolated so a secondary provider can still recover.
 */
class PriorityMetadataProvider(
    private val providers: List<MetadataProvider> = listOf(
        ITunesMetadataProvider(),
        MusicBrainzMetadataProvider(),
    ),
) : MetadataProvider {
    override suspend fun search(query: MetadataQuery): List<MetadataCandidate> {
        for (provider in providers) {
            val result = runCatching { provider.search(query) }
            if (result.isFailure) {
                StageGridDebugLog.state(
                    "METADATA",
                    "PROVIDER_FAILED provider=${provider.javaClass.simpleName} error=${result.exceptionOrNull()?.javaClass?.simpleName}",
                )
                continue
            }
            val candidates = result.getOrDefault(emptyList())
            if (candidates.isNotEmpty()) {
                StageGridDebugLog.state(
                    "METADATA",
                    "PROVIDER_SELECTED provider=${provider.javaClass.simpleName} candidates=${candidates.size}",
                )
                return candidates
            }
            StageGridDebugLog.state("METADATA", "PROVIDER_EMPTY provider=${provider.javaClass.simpleName}")
        }
        return emptyList()
    }
}
