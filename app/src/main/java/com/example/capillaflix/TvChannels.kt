package com.example.capillaflix

internal fun adjacentTelevision(currentId: String?, step: Int): Station? {
    val channels = Catalog.tv
    val index = channels.indexOfFirst { it.id == currentId }
    if (index < 0) return null
    return channels[Math.floorMod(index + step, channels.size)]
}
