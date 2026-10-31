// Compile-time stub of the CatVod Spider API. The real classes are provided by the host app
// (FongMi/TV, TVBox...), so this module is never packed into custom_spider.jar.
val androidJarPath: String by rootProject.extra
dependencies { "compileOnly"(files(androidJarPath)) }
