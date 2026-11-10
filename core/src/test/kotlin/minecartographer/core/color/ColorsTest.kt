package minecartographer.core.color

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ColorsTest {
    @Test
    fun `hsv and rgb convert both ways`() {
        for (rgb in listOf(0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF, 0x329061, 0xFFAA00, 0x808080, 0x123456)) {
            assertEquals(rgb, Hsv.of(rgb).toRgb(), "0x%06X".format(rgb))
        }
        assertEquals(Hsv(120f, 1f, 1f), Hsv.of(0x00FF00))
        assertEquals(0xFFFF00, Hsv(60f, 1f, 1f).toRgb())
        assertEquals(0xFF0000, Hsv(360f, 1f, 1f).toRgb(), "360 is the same hue as 0")
    }

    @Test
    fun `hex is read in the usual forms and written the same way`() {
        assertEquals(0x329061, HexColor.parse("#329061"))
        assertEquals(0x329061, HexColor.parse(" 329061 "))
        assertEquals(0xAABBCC, HexColor.parse("#abc"))
        assertNull(HexColor.parse("#12345"))
        assertNull(HexColor.parse("#12345G"))
        assertNull(HexColor.parse(""))
        assertEquals("#00FF7F", HexColor.format(0x00FF7F))
    }

    @Test
    fun `the 16 Minecraft colors are found and others snap to the nearest`() {
        assertEquals(12, MinecraftColors.indexOf(0xFF5555))
        assertNull(MinecraftColors.indexOf(0xFF5556))
        assertEquals(0xFF5555, MinecraftColors.nearest(0xF02020))
        assertEquals(0x000000, MinecraftColors.nearest(0x050505))
        assertEquals(16, MinecraftColors.NAMES.size)
    }
}
