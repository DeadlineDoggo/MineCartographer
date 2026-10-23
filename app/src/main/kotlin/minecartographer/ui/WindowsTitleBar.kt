package minecartographer.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout

/**
 * Makes the native Windows title bar match the dark editor (Windows 10 20H1+ for dark mode,
 * Windows 11 for the exact caption color). Uses the Java FFM API, so no JNA dependency.
 * Does nothing on other platforms or if the call fails.
 */
object WindowsTitleBar {
    private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
    private const val DWMWA_BORDER_COLOR = 34
    private const val DWMWA_CAPTION_COLOR = 35
    private const val DWMWA_TEXT_COLOR = 36

    fun applyDark(hwnd: Long, caption: Color, text: Color) {
        if (!System.getProperty("os.name").startsWith("Windows") || hwnd == 0L) return
        runCatching {
            Arena.ofConfined().use { arena ->
                val dwm = SymbolLookup.libraryLookup("dwmapi", arena)
                val setAttribute = Linker.nativeLinker().downcallHandle(
                    dwm.find("DwmSetWindowAttribute").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
                )
                val window = MemorySegment.ofAddress(hwnd)
                fun set(attribute: Int, value: Int) {
                    val v = arena.allocate(ValueLayout.JAVA_INT)
                    v.set(ValueLayout.JAVA_INT, 0, value)
                    setAttribute.invokeWithArguments(window, attribute, v, 4)
                }
                set(DWMWA_USE_IMMERSIVE_DARK_MODE, 1)
                set(DWMWA_CAPTION_COLOR, caption.toColorRef())
                set(DWMWA_BORDER_COLOR, caption.toColorRef())
                set(DWMWA_TEXT_COLOR, text.toColorRef())
            }
        }.onFailure { System.err.println("Could not style the title bar: $it") }
    }

    /** Win32 COLORREF is 0x00BBGGRR. */
    private fun Color.toColorRef(): Int {
        val argb = toArgb()
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (b shl 16) or (g shl 8) or r
    }
}
