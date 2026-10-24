package minecartographer

import java.util.Properties

object AppInfo {
    const val NAME = "MineCartographer"
    const val AUTHOR = "Emanuel Cisar"
    const val REPOSITORY_URL = "https://github.com/DeadlineDoggo/MineCartographer"
    const val LICENSE_URL = "$REPOSITORY_URL/blob/main/LICENSE"

    /** Where "Buy me a coffee" goes. Left empty, the button only says the link is not set up. */
    const val SUPPORT_URL = "https://ko-fi.com/mrkuko"

    /** From `appVersion` in gradle.properties, e.g. "0.1.0-alpha". */
    val version: String by lazy {
        val props = Properties()
        AppInfo::class.java.getResourceAsStream("/version.properties")?.use(props::load)
        props.getProperty("version") ?: "dev"
    }

    /** "v0.1.0 alpha" for display. */
    val versionLabel: String get() = "v" + version.replace('-', ' ')
}
