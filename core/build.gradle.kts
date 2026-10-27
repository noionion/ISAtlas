plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}
kotlin { jvmToolchain(21) }
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}
tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    testImplementation("junit:junit:4.13.2")
}
sourceSets.test {
    resources.srcDir("../app/src/main/assets")
    resources.srcDir("../data/official-packages/manual")
}

tasks.register<JavaExec>("validateLocalPackage") {
    group = "verification"
    description = "Validate a local ZIP using the same offline validator as Android (-PlocalPackage=path)."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.isatlas.core.LocalPackageTool")
    val input = providers.gradleProperty("localPackage").orNull
    args(input?.let { rootProject.file(it).absolutePath } ?: "", rootProject.file("app/src/main/assets/march-extensions.json").absolutePath)
}
