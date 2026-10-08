import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }
    // The iPhone app links these as a framework; building it needs a Mac, compiling the klibs does not.
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // Generator parameters are a user-editable JSON file, so the config types are serializable here.
            api(libs.kotlinx.serialization.json)
            api(libs.kotlinx.datetime)
        }
        jvmTest.dependencies {
            implementation(libs.junit)
        }
    }
}
