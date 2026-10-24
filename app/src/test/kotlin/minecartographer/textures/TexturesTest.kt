package minecartographer.textures

import minecartographer.core.BlockState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TexturesTest {
    @Test
    fun `parses model json`() {
        val json = Json.parse("""{ "parent": "block/cube_all", "textures": { "all": "block/stone" }, "elements": [ { "from": [0, 0.5, -1e1] } ], "ok": true, "n": null }""")
        val map = json as Map<*, *>
        assertEquals("block/cube_all", map["parent"])
        assertEquals(mapOf("all" to "block/stone"), map["textures"])
        assertEquals(listOf(0.0, 0.5, -10.0), ((map["elements"] as List<*>)[0] as Map<*, *>)["from"])
        assertEquals(true, map["ok"])
        assertTrue("n" in map && map["n"] == null)
    }

    @Test
    fun `faces point outwards`() {
        val from = floatArrayOf(0f, 0f, 0f)
        val to = floatArrayOf(16f, 16f, 16f)
        for (dir in 0 until 6) {
            val q = ModelBuilder.face(dir, from, to, null, 0, null, 0, -1, true, -1, null)
            assertEquals(Dir.DX[dir].toFloat(), q.nx, 1e-5f, "x of ${Dir.NAMES[dir]}")
            assertEquals(Dir.DY[dir].toFloat(), q.ny, 1e-5f, "y of ${Dir.NAMES[dir]}")
            assertEquals(Dir.DZ[dir].toFloat(), q.nz, 1e-5f, "z of ${Dir.NAMES[dir]}")
        }
    }

    @Test
    fun `a quarter turn of the model turns north into east`() {
        // Variant "y": 90 rotates the model clockwise seen from above.
        val q = ModelBuilder.face(Dir.NORTH, floatArrayOf(0f, 0f, 0f), floatArrayOf(16f, 16f, 16f), null, 0, null, 0, -1, true, Dir.NORTH, null, yRot = 1)
        assertEquals(1f, q.nx, 1e-5f)
        assertEquals(Dir.EAST, q.cull)
    }

    @Test
    fun `old fences connect to their neighbors`() {
        val fence = BlockState.of("oak_fence")
        val stone = BlockState.of("stone")
        val world = mapOf((1 to 0) to fence, (0 to -1) to stone)
        val derived = Connections.derive(fence, { dx, _, dz -> world[dx to dz] ?: BlockState.AIR }) { it === stone }
        assertEquals(mapOf("north" to "true", "south" to "false", "west" to "false", "east" to "true"), derived.properties)
    }

    @Test
    fun `grass under snow is snowy`() {
        val grass = BlockState.of("grass_block")
        val derived = Connections.derive(grass, { _, dy, _ -> if (dy == 1) BlockState.of("snow") else null }) { false }
        assertEquals("true", derived.properties["snowy"])
    }
}
