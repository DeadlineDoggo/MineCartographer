package minecartographer.ui

import androidx.compose.ui.graphics.Color

/** Dark editor palette. */
object EditorTheme {
    val viewportBackground = Color(0xFF17181B)
    val placeholder = Color(0xFF26282D)

    val window = Color(0xFF1E1F22)
    val toolbar = Color(0xFF2B2D30)
    val navbar = Color(0xFF2B2D30)
    val panel = Color(0xFF232427)
    val panelRaised = Color(0xFF2E3034)
    val border = Color(0xFF393B40)

    val text = Color(0xFFDFE1E5)
    val textDim = Color(0xFF9A9DA5)
    val textFaint = Color(0xFF6C707A)

    val accent = Color(0xFF4F9DFF)
    val accentSoft = Color(0x334F9DFF)
    val hover = Color(0x14FFFFFF)

    /** Paste preview, edited (pasted) chunks, erased chunks. */
    val paste = Color(0xFF3DDC97)
    val edited = Color(0xFFFFB547)
    val deleted = Color(0xFFFF5D5D)
    val warning = Color(0xFFE5A23A)

    /** Fill of the original world's layer thumbnail. */
    val grassTop = Color(0xFF6DAA45)
}
