package minecartographer.core.markers

/** What the app knows about a waypoint it shows: the values a user can change. [rgb] is 0xRRGGBB, [y] null when unknown. */
data class WaypointData(val name: String, val x: Int, val y: Int?, val z: Int, val rgb: Int)

/** A waypoint made in the app. [issue] is set while it still has to be resolved (see [WaypointOverlay.reconcile]). */
data class OwnWaypoint(val id: Int, val data: WaypointData, val issue: Boolean = false)

/** A waypoint as it is shown and exported: [marker] with the changes applied; [ownId] is set for [WaypointOrigin.Own] ones. */
data class ShownWaypoint(val marker: Marker, val origin: WaypointOrigin, val ownId: Int?, val issue: Boolean)

/**
 * What the app changed about the waypoints of one world, kept apart from the minimap mods' files (which are
 * never edited): [edits] by the key of the mod waypoint (null = deleted), plus the waypoints made in the app
 * ([own]). Shown and exported is the mods' list with this applied ([apply]). Immutable: every change returns a new overlay.
 */
data class WaypointOverlay(
    val edits: Map<WaypointKey, WaypointData?> = emptyMap(),
    val own: List<OwnWaypoint> = emptyList(),
    val nextId: Int = 1,
) {
    val isEmpty: Boolean get() = edits.isEmpty() && own.isEmpty()

    /** Own waypoints that still have to be resolved. */
    val issueCount: Int get() = own.count { it.issue }

    // ---- Changing a mod waypoint ----

    fun edit(key: WaypointKey, data: WaypointData) = copy(edits = edits + (key to data))
    fun delete(key: WaypointKey) = copy(edits = edits + (key to null))

    /** Back to what the mod file has. */
    fun restore(key: WaypointKey) = copy(edits = edits - key)

    // ---- Own waypoints ----

    /** Adds a waypoint; the new overlay and the id of the new waypoint. */
    fun add(data: WaypointData): Pair<WaypointOverlay, Int> = copy(own = own + OwnWaypoint(nextId, data), nextId = nextId + 1) to nextId

    fun editOwn(id: Int, data: WaypointData) = copy(own = own.map { if (it.id == id) it.copy(data = data) else it })
    fun deleteOwn(id: Int) = copy(own = own.filter { it.id != id })

    // ---- Resolving an issue ----

    /** "Keep as my own": the waypoint stays as it is and is no longer an issue. */
    fun keepAsOwn(id: Int) = copy(own = own.map { if (it.id == id) it.copy(issue = false) else it })

    /** "Assign to": the waypoint becomes the edit of the mod waypoint [key] (which has to exist) and stops being an own one. */
    fun assign(id: Int, key: WaypointKey): WaypointOverlay {
        val item = own.firstOrNull { it.id == id } ?: return this
        return copy(edits = edits + (key to item.data), own = own.filter { it.id != id })
    }

    // ---- Against the mods' files ----

    /**
     * Compares with the waypoints [mod] has now. An edit whose mod waypoint is gone loses its key and becomes an own
     * waypoint flagged as an issue, so nothing the user changed is lost; a deletion whose waypoint is gone is dropped
     * (there is nothing left to delete). Returns this when nothing had to change.
     */
    fun reconcile(mod: List<Marker>): WaypointOverlay {
        val keys = mod.mapNotNullTo(HashSet()) { it.key }
        val gone = edits.keys.filter { it !in keys }
        if (gone.isEmpty()) return this
        var next = nextId
        val orphans = gone.sortedWith(compareBy({ it.source }, { it.file }, { it.name }, { it.x }, { it.z })).mapNotNull { key ->
            edits[key]?.let { OwnWaypoint(next++, it, issue = true) }
        }
        return copy(edits = edits.filterKeys { it in keys }, own = own + orphans, nextId = next)
    }

    /** The waypoints of [mod] (map banners are ignored) with the changes applied, then the own ones. */
    fun apply(mod: List<Marker>): List<ShownWaypoint> = buildList {
        for (m in mod) {
            if (m.kind != MarkerKind.Waypoint) continue
            val key = m.key
            if (key == null || key !in edits) {
                add(ShownWaypoint(m, WaypointOrigin.Mod, null, false))
                continue
            }
            val data = edits.getValue(key) ?: continue // deleted
            val shown = m.copy(name = data.name, x = data.x, y = data.y, z = data.z, rgb = data.rgb)
            add(ShownWaypoint(shown, if (shown == m) WaypointOrigin.Mod else WaypointOrigin.Edited, null, false))
        }
        for (o in own) add(ShownWaypoint(
            Marker(MarkerKind.Waypoint, o.data.name, o.data.x, o.data.y, o.data.z, o.data.rgb, OWN_SOURCE),
            WaypointOrigin.Own, o.id, o.issue,
        ))
    }

    companion object {
        /** `source` of the markers of own waypoints. */
        const val OWN_SOURCE = "MineCartographer"
    }
}
