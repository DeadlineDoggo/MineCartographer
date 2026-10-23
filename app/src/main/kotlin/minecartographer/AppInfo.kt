package minecartographer

import java.util.Properties

object AppInfo {
    const val NAME = "MineCartographer"

    /** From `appVersion` in gradle.properties, e.g. "0.1.0-alpha". */
    val version: String by lazy {
        val props = Properties()
        AppInfo::class.java.getResourceAsStream("/version.properties")?.use(props::load)
        props.getProperty("version") ?: "dev"
    }

    /** "v0.1.0 alpha" for display. */
    val versionLabel: String get() = "v" + version.replace('-', ' ')
}
