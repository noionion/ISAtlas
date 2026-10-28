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
val dataAssets = providers.gradleProperty("isatlas.dataDir").map { rootProject.file(it) }
    .getOrElse(rootProject.file("app/src/main/assets"))
val prepareBuiltinDataTestResources by tasks.registering(Sync::class) {
    from(zipTree(dataAssets.resolve("packages/manual.zip"))) {
        include("inst/**", "riscv-instructions.json")
    }
    into(layout.buildDirectory.dir("generated/builtin-data-test-resources"))
}
sourceSets.test {
    resources.srcDir(dataAssets)
    resources.srcDir(prepareBuiltinDataTestResources)
    resources.srcDir("../app/src/androidTest/assets")
}

tasks.register<JavaExec>("validateOfficialPackages") {
    group = "verification"
    description = "Validate every official package combination (-PofficialDataDirectory=path)."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.isatlas.core.OfficialPackageTool")
    args(providers.gradleProperty("officialDataDirectory").map { rootProject.file(it).absolutePath }
        .getOrElse(dataAssets.absolutePath))
}

tasks.register<JavaExec>("validateLocalPackage") {
    group = "verification"
    description = "Validate a local ZIP using the same offline validator as Android (-PlocalPackage=path)."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.isatlas.core.LocalPackageTool")
    val input = providers.gradleProperty("localPackage").orNull
    args(input?.let { rootProject.file(it).absolutePath } ?: "", rootProject.file("app/src/main/assets/march-extensions.json").absolutePath)
}
