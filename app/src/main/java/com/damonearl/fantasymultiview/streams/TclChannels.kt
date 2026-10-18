package com.damonearl.fantasymultiview.streams

import android.content.Context
import android.util.Log

/**
 * TCL TVs keep scanned antenna channels in their own database and hide them from the system TV
 * provider. They're read here through TCL's tvmanager library, which sits on the boot classpath of
 * TCL TVs (so it's reached by reflection), and tuned by key: TCL's tuner accepts
 * content://android.media.tv/channel/<key> with a "dbprimarykey" tune parameter.
 */
internal object TclChannels {
    const val TUNER_PACKAGE = "com.tcl.tvinput"
    const val KEY_PARAM = "dbprimarykey"

    /** Same query TCL's own tuner uses for over-the-air channels. */
    private const val SELECTION = "delivery_type=3 and service_type=1 or service_type=3 or service_type=2"

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

    private const val TAG = "TclChannels"
}
