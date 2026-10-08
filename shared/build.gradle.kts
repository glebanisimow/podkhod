import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

kotlin {
    android {
        namespace = "app.podkhod.shared"
        compileSdk = 37
        minSdk = 29
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
        androidResources {
            enable = true
        }
    }
    // The iPhone app links these as a framework; building it needs a Mac, compiling the klibs does not.
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    compilerOptions {
        // expect/actual classes (the Room database constructor) are still Beta.
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":domain"))

            implementation(libs.cmp.runtime)
            implementation(libs.cmp.foundation)
            implementation(libs.cmp.ui)
            implementation(libs.cmp.ui.backhandler)
            implementation(libs.cmp.ui.tooling.preview)
            implementation(libs.cmp.components.resources)
            implementation(libs.cmp.material3)
            implementation(libs.jb.lifecycle.runtime.compose)
            implementation(libs.jb.lifecycle.viewmodel.compose)
            implementation(libs.jb.navigation.compose)

            api(libs.room.runtime)
            implementation(libs.sqlite.bundled)
            api(libs.datastore.preferences.core)

            api(project.dependencies.platform(libs.koin.bom))
            api(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)

            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.io.core)
            implementation(libs.coil.compose)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
            implementation(libs.coil.gif)
        }
    }
}

compose.resources {
    packageOfResClass = "app.podkhod.resources"
    publicResClass = true
}

room {
    schemaDirectory("$projectDir/schemas")
}

ksp {
    arg("room.generateKotlin", "true")
}

dependencies {
    listOf("kspAndroid", "kspIosArm64", "kspIosSimulatorArm64").forEach { add(it, libs.room.compiler) }
}
