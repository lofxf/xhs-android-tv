import java.security.MessageDigest

val androidJarPath: String by rootProject.extra
val d8Path: String by rootProject.extra

val bundled by configurations.creating

dependencies {
    "compileOnly"(project(":catvod-stub"))
    "compileOnly"(files(androidJarPath))
    "implementation"("com.google.zxing:core:3.5.3")
    bundled("com.google.zxing:core:3.5.3") { isTransitive = false }
}

val dexOut = layout.buildDirectory.file("dex/custom_spider.jar")

/** Converts the spider classes (+ zxing) into a dex jar that FongMi/TV's DexClassLoader can load. */
val dexJar by tasks.registering(Exec::class) {
    group = "build"
    val jarTask = tasks.named<Jar>("jar")
    dependsOn(jarTask, ":catvod-stub:jar")
    inputs.files(jarTask, bundled)
    outputs.file(dexOut)
    doFirst {
        require(androidJarPath.isNotEmpty()) { "android.jar not found: set ANDROID_HOME or -PandroidJar=..." }
        dexOut.get().asFile.parentFile.mkdirs()
        commandLine(
            listOf(d8Path, "--release", "--min-api", "21",
                "--lib", androidJarPath,
                "--classpath", project(":catvod-stub").tasks.named<Jar>("jar").get().archiveFile.get().asFile.path,
                "--output", dexOut.get().asFile.path,
                jarTask.get().archiveFile.get().asFile.path) + bundled.files.map { it.path }
        )
    }
}

/** dist/: custom_spider.jar + config.json ready to be hosted for FongMi/TV. */
tasks.register("spiderJar") {
    group = "build"
    dependsOn(dexJar)
    doLast {
        val dist = rootProject.file("dist").apply { mkdirs() }
        val jar = dexOut.get().asFile.copyTo(File(dist, "custom_spider.jar"), overwrite = true)
        val md5 = MessageDigest.getInstance("MD5").digest(jar.readBytes()).joinToString("") { "%02x".format(it) }
        File(dist, "custom_spider.jar.md5").writeText(md5)
        val template = rootProject.file("config.template.json").readText()
        File(dist, "config.json").writeText(template.replace("{{MD5}}", md5))
        println("custom_spider.jar md5=$md5 -> ${dist.path}")
    }
}
