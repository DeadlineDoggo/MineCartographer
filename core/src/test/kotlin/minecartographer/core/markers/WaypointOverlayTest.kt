package minecartographer.core.markers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class WaypointOverlayTest {
    private fun mod(name: String, x: Int, file: String = "waypoints.txt"): Marker {
        val key = WaypointKey("Xaero's Minimap", file, name, x, 64, 0)
        return Marker(MarkerKind.Waypoint, name, x, 64, 0, 0xFF5555, "Xaero's Minimap", key)
    }
    private val home = mod("Home", 1)
    private val mine = mod("Mine", 2)
    private val banner = Marker(MarkerKind.Banner, "Banner", 9, 9, 9, 0xFFFFFF, "Map banner")
    private fun data(name: String, x: Int = 0) = WaypointData(name, x, 70, 0, 0x55FF55)

    @Test
    fun `an empty overlay shows the mod waypoints as they are and ignores banners`() {
        val shown = WaypointOverlay().apply(listOf(home, banner, mine))
        assertEquals(listOf(home, mine), shown.map { it.marker })
        assertEquals(listOf(WaypointOrigin.Mod, WaypointOrigin.Mod), shown.map { it.origin })
    }

    @Test
    fun `edits change a waypoint, deletions remove it, own ones come last`() {
        val (withOwn, id) = WaypointOverlay().edit(home.key!!, data("Base", 5)).delete(mine.key!!).add(data("Mine of mine", 8))
        val shown = withOwn.apply(listOf(home, mine))
        assertEquals(listOf("Base", "Mine of mine"), shown.map { it.marker.name })
        assertEquals(listOf(WaypointOrigin.Edited, WaypointOrigin.Own), shown.map { it.origin })
        assertEquals(home.key, shown[0].marker.key, "an edited waypoint keeps the key it was read with")
        assertEquals(id, shown[1].ownId)
        assertEquals(WaypointOverlay.OWN_SOURCE, shown[1].marker.source)
    }

    @Test
    fun `an edit that changes nothing counts as the mod's waypoint, restore drops the edit`() {
        val same = WaypointData(home.name, home.x, home.y, home.z, home.rgb)
        val overlay = WaypointOverlay().edit(home.key!!, same)
        assertEquals(WaypointOrigin.Mod, overlay.apply(listOf(home)).single().origin)
        assertEquals(WaypointOverlay(), overlay.restore(home.key!!))
    }

    @Test
    fun `an edit whose mod waypoint is gone becomes an own waypoint with an issue`() {
        val overlay = WaypointOverlay().edit(home.key!!, data("Base", 5)).delete(mine.key!!)
        val reconciled = overlay.reconcile(listOf(mod("Else", 3)))
        assertEquals(emptyMap(), reconciled.edits, "the deletion of a waypoint that is gone is dropped")
        assertEquals(listOf(OwnWaypoint(1, data("Base", 5), issue = true)), reconciled.own)
        assertEquals(1, reconciled.issueCount)
        assertEquals(2, reconciled.nextId)
        assertEquals(listOf("Else", "Base"), reconciled.apply(listOf(mod("Else", 3))).map { it.marker.name })
        assertEquals(listOf(false, true), reconciled.apply(listOf(mod("Else", 3))).map { it.issue })
    }

    @Test
    fun `reconcile changes nothing when every edit still has its waypoint`() {
        val overlay = WaypointOverlay().edit(home.key!!, data("Base")).delete(mine.key!!)
        assertSame(overlay, overlay.reconcile(listOf(home, mine)))
    }

    @Test
    fun `an issue is resolved by keeping it, assigning it to a mod waypoint or deleting it`() {
        val issue = WaypointOverlay().edit(home.key!!, data("Base", 5)).reconcile(emptyList())
        assertEquals(0, issue.keepAsOwn(1).issueCount)
        assertEquals(1, issue.keepAsOwn(1).own.size)
        assertEquals(emptyList(), issue.deleteOwn(1).own)

        val assigned = issue.assign(1, mine.key!!)
        assertEquals(emptyList(), assigned.own)
        assertEquals(data("Base", 5), assigned.edits[mine.key!!])
        assertEquals(listOf("Base"), assigned.apply(listOf(mine)).map { it.marker.name })
        assertSame(issue, issue.assign(99, mine.key!!))
    }

    @Test
    fun `own waypoints can be edited and get new ids`() {
        val (a, id1) = WaypointOverlay().add(data("A"))
        val (b, id2) = a.add(data("B"))
        assertEquals(listOf(1, 2), listOf(id1, id2))
        assertEquals("A2", b.editOwn(id1, data("A2")).own.first().data.name)
        assertNull(b.deleteOwn(id1).own.firstOrNull { it.id == id1 })
        assertEquals(3, b.deleteOwn(id1).nextId, "an id is not used again")
    }

    @Test
    fun `deleted waypoints can be listed and brought back`() {
        val overlay = WaypointOverlay().delete(mine.key!!).edit(home.key!!, data("Base"))
        assertEquals(listOf(mine), overlay.deletedFrom(listOf(home, mine, banner)))
        assertEquals(emptyList(), overlay.restore(mine.key!!).deletedFrom(listOf(home, mine)))
        assertEquals(WaypointData("Home", 1, 64, 0, 0xFF5555), WaypointData.of(home))
    }
}
