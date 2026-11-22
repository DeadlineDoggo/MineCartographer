package minecartographer.viewport

/**
 * Keeps the tiles of one screen within the tile cache. Every frame draws all its tiles, so when they don't fit
 * together, each new tile pushes out one still on screen, which is rendered again and pushes out the next: the map
 * flickers and the work never ends (the Cave view's tall tiles do that at some zooms). From the tiles of a frame this
 * guesses how much a screen takes at each LOD, and caps the LOD for the next frames at the finest that fits.
 */
internal class LodBudget(private val share: Double = 0.7) {
    /** Bytes per chunk at LOD 1 (without kept pixels), summed over the tiles counted this frame. */
    private var sum = 0.0
    private var counted = 0
    private var missing = 0

    /** The finest LOD the screen fits at, as of the last frame. */
    var cap = IsoView.LODS.last()
        private set

    /** The LOD to render at: the one [wanted] for the zoom, unless the screen wouldn't fit. */
    fun lodFor(wanted: Int) = minOf(wanted, cap)

    /** A tile on screen, at whatever LOD it was found. */
    fun add(tile: ChunkTile) {
        sum += tile.byteSize.toDouble() / (tile.lod.toDouble() * tile.lod * keep(tile.lod))
        counted++
    }

    /** A chunk on screen whose tile is still being rendered: counted as an average one. */
    fun missing() {
        missing++
    }

    /** Ends the frame: works out [cap] for the next one from what [maxBytes] of tiles the cache keeps. */
    fun endFrame(maxBytes: Long) {
        if (counted > 0) {
            val screen = sum / counted * (counted + missing)
            cap = IsoView.LODS.lastOrNull { lod -> screen * lod * lod * keep(lod) <= maxBytes * share } ?: IsoView.LODS.first()
        }
        sum = 0.0
        counted = 0
        missing = 0
    }

    /** Tiles at the LODs that are composited keep their pixels too, twice the memory. */
    private fun keep(lod: Int) = if (lod <= ChunkTileCache.MAX_BLOCK_LOD) 2.0 else 1.0
}
