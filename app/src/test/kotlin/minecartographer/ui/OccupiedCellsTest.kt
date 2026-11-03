package minecartographer.ui

import minecartographer.model.ElementRotation
import minecartographer.model.ModelDoc
import minecartographer.model.ModelElement
import minecartographer.model.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals

class OccupiedCellsTest {
    private fun doc(vararg e: ModelElement) = ModelDoc(e.toList(), nextId = 100)

    private fun box(id: Int, from: Vec3, to: Vec3, rotation: ElementRotation? = null) = ModelElement(id, "e$id", from, to, rotation)

    @Test
    fun `an empty model and one inside a block have just the block at the origin`() {
        assertEquals(setOf(Cell(0, 0, 0)), occupiedCells(ModelDoc(), emptySet()))
        assertEquals(setOf(Cell(0, 0, 0)), occupiedCells(doc(box(1, Vec3(0f, 0f, 0f), Vec3(16f, 16f, 16f))), emptySet()), "a box that ends on the border stays in its block")
    }

    @Test
    fun `parts that reach into other blocks bring those blocks in`() {
        // A bed: two blocks long along z, and a part that hangs below the floor.
        val bed = doc(box(1, Vec3(0f, 0f, 0f), Vec3(16f, 8f, 32f)), box(2, Vec3(2f, -4f, 2f), Vec3(4f, 0f, 4f)))
        assertEquals(setOf(Cell(0, 0, 0), Cell(0, 0, 1), Cell(0, -1, 0)), occupiedCells(bed, emptySet()))
        // Blocks without parts in them are left out: an L shape is not its bounding rectangle.
        val l = doc(box(1, Vec3(0f, 0f, 0f), Vec3(32f, 4f, 4f)), box(2, Vec3(0f, 0f, 16f), Vec3(4f, 4f, 20f)))
        assertEquals(setOf(Cell(0, 0, 0), Cell(1, 0, 0), Cell(0, 0, 1)), occupiedCells(l, emptySet()))
    }

    @Test
    fun `rotation counts and hidden parts do not`() {
        // A square plate turned 45 degrees about its middle reaches over all four borders with its corners.
        val plate = box(1, Vec3(0f, 0f, 0f), Vec3(16f, 2f, 16f), ElementRotation(1, 45f, Vec3(8f, 1f, 8f)))
        assertEquals(setOf(Cell(0, 0, 0)), occupiedCells(doc(plate.copy(rotation = null)), emptySet()))
        val turned = occupiedCells(doc(plate), emptySet())
        assertEquals((-1..1).flatMap { x -> (-1..1).map { z -> Cell(x, 0, z) } }.toSet(), turned)
        assertEquals(setOf(Cell(0, 0, 0)), occupiedCells(doc(box(1, Vec3(0f, 0f, 0f), Vec3(48f, 4f, 4f))), setOf(1)))
    }
}
