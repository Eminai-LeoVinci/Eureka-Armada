package org.valkyrienskies.eureka.blueprint

/**
 * How a blueprint page gets opened.
 *
 * The same indirection [org.valkyrienskies.eureka.path.PathMessages] uses, and for the same reason: the screen
 * is a client class and screens are the loader's business, not `:common`'s. The loader installs [opener] during
 * client init; until it does -- or on a dedicated server, which reaches this code and must not touch a screen
 * class -- opening a page is simply a no-op rather than a crash.
 *
 * Nothing here is a packet. A drafted page carries its whole manifest in its item component, so the client
 * already holds everything the screen draws.
 */
object BlueprintPages {

    /** Installed by the loader's client entrypoint. Null on a server, and until client init runs. */
    @Volatile
    @JvmStatic
    var opener: ((Blueprint.Page) -> Unit)? = null

    fun open(page: Blueprint.Page) {
        opener?.invoke(page)
    }
}
