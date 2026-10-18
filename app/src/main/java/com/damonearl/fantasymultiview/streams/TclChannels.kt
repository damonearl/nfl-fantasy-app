package com.damonearl.fantasymultiview.streams

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.tv.TvContract
import android.util.Log

/**
 * TCL TVs keep scanned antenna channels in their own database and hide them from the system TV
 * provider. They're read here through TCL's tvmanager library, which sits on the boot classpath of
 * TCL TVs (so it's reached by reflection).
 *
 * Tuning: TCL's tuner picks the station from a "dbprimarykey" tune parameter, but first looks up
 * the channel URI's row in the system TV provider to check its type, and silently stays on the
 * current channel if that row is missing. TCL's own rows are hidden from us, so we add one ATSC
 * "carrier" row of our own and tune every station through its URI.
 */
internal object TclChannels {
    const val TUNER_PACKAGE = "com.tcl.tvinput"
    const val KEY_PARAM = "dbprimarykey"

    /** Same query TCL's own tuner uses for over-the-air channels. */
    private const val SELECTION = "delivery_type=3 and service_type=1 or service_type=3 or service_type=2"

    /** [key] picks the station when sent as [KEY_PARAM]. */
    data class Entry(val key: Int, val number: String, val name: String)

    /** All scanned channels, or null if this isn't a TCL TV or the library refused. */
    fun load(context: Context): List<Entry>? = runCatching {
        val manager = Class.forName("tvos.tv.TManager").getMethod("getInstance", Context::class.java).invoke(null, context)
        val channelManager = manager.javaClass.getMethod("getChannelManager").invoke(manager)
        val iterator = channelManager.javaClass
            .getMethod("queryTVServices", String::class.java, Array<String>::class.java, String::class.java)
            .invoke(channelManager, SELECTION, null, null) ?: return@runCatching emptyList()
        val hasNext = iterator.javaClass.getMethod("hasNext")
        val next = iterator.javaClass.getMethod("next")
        buildList {
            while (hasNext.invoke(iterator) as Boolean) {
                val service = next.invoke(iterator) ?: continue
                val type = service.javaClass
                // TCL packs ATSC numbers as major << 8 | minor: 513 is 2.1.
                val packed = type.getMethod("getNumber").invoke(service) as Int
                add(
                    Entry(
                        key = type.getMethod("getKey").invoke(service) as Int,
                        number = "${packed shr 8}.${packed and 0xFF}",
                        name = (type.getMethod("getName").invoke(service) as String?).orEmpty().trim(),
                    )
                )
            }
        }.distinctBy { it.number }.sortedWith(compareBy({ it.number.substringBefore('.').toInt() }, { it.number.substringAfter('.').toInt() }))
    }.onFailure { Log.w(TAG, "couldn't read TCL channel list", it) }.getOrNull()

    /** Our ATSC carrier row's id in the system TV provider, adding it if needed; null if refused. */
    fun carrierRowId(context: Context): Long? = runCatching {
        val resolver = context.contentResolver
        // Without full EPG access, queries return only this app's own rows (no selection allowed).
        resolver.query(TvContract.Channels.CONTENT_URI, arrayOf(TvContract.Channels._ID, TvContract.Channels.COLUMN_INPUT_ID), null, null, null)
            ?.use { c ->
                while (c.moveToNext()) if (c.getString(1) == CARRIER_INPUT) return@runCatching c.getLong(0)
            }
        val values = ContentValues().apply {
            put(TvContract.Channels.COLUMN_INPUT_ID, CARRIER_INPUT)
            put(TvContract.Channels.COLUMN_TYPE, TvContract.Channels.TYPE_ATSC_T)
            put(TvContract.Channels.COLUMN_SERVICE_TYPE, TvContract.Channels.SERVICE_TYPE_AUDIO_VIDEO)
            put(TvContract.Channels.COLUMN_DISPLAY_NAME, "Fantasy Multiview tuner")
            put(TvContract.Channels.COLUMN_DISPLAY_NUMBER, "0.0")
        }
        resolver.insert(TvContract.Channels.CONTENT_URI, values)?.let(ContentUris::parseId)
    }.onFailure { Log.w(TAG, "couldn't add carrier channel row", it) }.getOrNull()

    /** Not a real input: the row only has to exist with an ATSC type for TCL's check. */
    private const val CARRIER_INPUT = "com.damonearl.fantasymultiview/.TclCarrier"
    private const val TAG = "TclChannels"
}
