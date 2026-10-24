import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

kotlin {
    jvmToolchain(25)
}

val appVersion = providers.gradleProperty("appVersion").get()
version = appVersion

// Lets the app show its version (see AppInfo.kt).
tasks.processResources {
    val v = appVersion // local copy: the configuration cache can't serialize script references
    inputs.property("appVersion", v)
    filesMatching("version.properties") { expand("version" to v) }
}

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    // Dispatchers.Main (the Swing/AWT event thread) for handing background results back to the UI.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

compose.desktop {
    application {
        mainClass = "minecartographer.MainKt"
        jvmArgs += listOf(
            "-Xmx4g",
            "--enable-native-access=ALL-UNNAMED", // Skia and the Windows title bar call native code
            "-splash:\$APPDIR/resources/splash.png", // shown by the JVM before the UI loads (packaged app)
        )

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "MineCartographer"
            packageVersion = appVersion.substringBefore('-')
            vendor = "Emanuel Cisar"
            copyright = "© 2026 Emanuel Cisar. GNU GPL v3."
            licenseFile = rootProject.layout.projectDirectory.file("LICENSE")
            appResourcesRootDir = layout.projectDirectory.dir("resources")
            windows {
                iconFile = layout.projectDirectory.file("icons/app.ico")
                menuGroup = "MineCartographer"
                shortcut = true
            }
            linux {
                iconFile = layout.projectDirectory.file("icons/app.png")
            }
        }
    }
}

// `$APPDIR` only exists in the packaged app; point `run` at the splash in the source tree instead.
val devSplash = layout.projectDirectory.file("resources/common/splash.png").asFile.absolutePath
afterEvaluate {
    tasks.named<JavaExec>("run") {
        val args = jvmArgs.orEmpty().map { if (it.startsWith("-splash:")) "-splash:$devSplash" else it }
        jvmArgs = args
    }
}

tasks.register<JavaExec>("generateIcons") {
    group = "build"
    description = "Renders the app icon to icons/app.png, icons/app.ico and resources/common/splash.png"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "minecartographer.tools.IconGeneratorKt"
    args(layout.projectDirectory.asFile.absolutePath)
}
