import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(libs.junit)
}

// 가상 운전자 실험은 평소엔 짧은 점검만. 전체 실험: ./gradlew :core:test -Psim=1 [-Pseeds=20] --tests '*VirtualDriveExperiment*'
tasks.test {
    val sim = (project.findProperty("sim") ?: "").toString()
    val seeds = (project.findProperty("seeds") ?: "").toString()
    val debug = (project.findProperty("debug") ?: "").toString()
    inputs.property("sim", sim)
    inputs.property("seeds", seeds)
    inputs.property("debug", debug)
    systemProperty("nunchi.sim", sim)
    systemProperty("nunchi.seeds", seeds)
    systemProperty("nunchi.debug", debug)
    testLogging { showStandardStreams = debug == "1" || sim == "1" }
}
