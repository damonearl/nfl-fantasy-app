package com.damonearl.fantasymultiview.streams

import com.damonearl.fantasymultiview.data.AppSettings
import com.damonearl.fantasymultiview.data.NflGame

/** Stream URLs the user pasted per game from the phone config page. */
class ManualStreamProvider : StreamProvider {
    override suspend fun find(game: NflGame, allGames: List<NflGame>, settings: AppSettings): StreamSource? =
        settings.manualStreams[game.id]
            ?.takeUnless { it.trim().startsWith(TvTunerProvider.TV_PREFIX, ignoreCase = true) } // the tuner provider handles "tv:4.1"
            ?.let { StreamSource.parse(it, label = "Custom", kind = SourceKind.MANUAL) }
}
