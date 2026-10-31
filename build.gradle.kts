import java.io.File
import java.util.Properties

/** Locate the Android SDK without needing the Android Gradle Plugin. */
fun sdkDir(): File? {
    val local = rootProject.file("local.properties")
    if (local.exists()) {
        val p = Properties().apply { local.inputStream().use { load(it) } }
        p.getProperty("sdk.dir")?.let { return File(it) }
    }
    return (System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT"))?.let { File(it) }
}

fun latestChild(dir: File, mustContain: String): File? =
    dir.listFiles()?.filter { File(it, mustContain).exists() }
        ?.maxByOrNull { it.name.filter(Char::isDigit).padEnd(8, '0').take(8).toLongOrNull() ?: 0L }

val androidJarPath: String by extra(
    (findProperty("androidJar") as String?)
        ?: sdkDir()?.let { latestChild(File(it, "platforms"), "android.jar") }?.let { File(it, "android.jar").path }
        ?: ""
)
val d8Path: String by extra(
    (findProperty("d8") as String?)
        ?: sdkDir()?.let { latestChild(File(it, "build-tools"), "d8") }?.let { File(it, "d8").path }
        ?: "d8"
)

subprojects {
    apply(plugin = "java-library")
    extensions.configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:-options"))
    }
}
