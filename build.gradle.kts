plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
    // Add application plugin so `./gradlew run` is available
    application

}

group = "org.example"
version = "2.0"

repositories {
    mavenCentral()

}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
    implementation("com.google.code.gson:gson:2.15.0")
    implementation("com.google.ortools:ortools-java:9.15.6755")
    implementation("org.jetbrains.lets-plot:lets-plot-kotlin:4.15.0")
    implementation("org.jetbrains.lets-plot:lets-plot-image-export:4.8.2")

    testImplementation(kotlin("test"))
}

kotlin {
    // Java 25 (LTS); the devcontainer uses the same JDK.
    jvmToolchain(25)
}

tasks.test {
    useJUnitPlatform()
    // OR-Tools loads its native library (the LP planner); Java 25 asks for that to be allowed explicitly.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // Test classes run side by side in this JVM (JUnit's parallel execution), a class's tests one after another unless
    // it says otherwise (FactoryTest). A free thread takes the next class, so the long ones spread out by themselves;
    // Gradle's maxParallelForks would deal classes to JVMs in turn by name, however long they take. @Isolated classes
    // run alone at the end: ExportedDataTest (swaps GameConstants' global tables for a moment), HeatTest and
    // RouterFuzzTest (they pass reliably only on an idle machine). At most half the cores' worth of tests at once:
    // the searches take 2-4 threads each, against time budgets. -PtestThreads=N overrides that.
    val testThreads = (project.findProperty("testThreads") as String?)?.toInt()
        ?: (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
    systemProperty("junit.jupiter.execution.parallel.enabled", "true")
    systemProperty("junit.jupiter.execution.parallel.mode.default", "same_thread")
    systemProperty("junit.jupiter.execution.parallel.mode.classes.default", "concurrent")
    systemProperty("junit.jupiter.execution.parallel.config.strategy", "fixed")
    systemProperty("junit.jupiter.execution.parallel.config.fixed.parallelism", testThreads)
    // Several searches share the heap (one at a time fit in Gradle's default 512 MB).
    maxHeapSize = "2g"
}

// Configure the application plugin: the Kotlin top-level `main` in `Main.kt` becomes class `MainKt`
application {
    // If your `main` is in a package, use the fully-qualified name, e.g. "com.example.MainKt"
    mainClass.set("MainKt")
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

tasks.named<JavaExec>("run") {
    // If 'ram' is passed from CLI, use it. Otherwise default to 2g (or whatever you had).
    val ram = project.findProperty("ram") ?: "6g"
    jvmArgs = listOf("-Xmx$ram", "--enable-native-access=ALL-UNNAMED")
}