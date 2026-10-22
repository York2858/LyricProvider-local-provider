/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

import com.android.build.api.dsl.ApplicationExtension

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    kotlin("plugin.serialization") version "2.1.21"
}

configure<ApplicationExtension> {
    namespace = "io.github.proify.lyricon.localprovider"
    testBuildType = providers.gradleProperty("localProviderTestBuildType").getOrElse("debug")
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "io.github.proify.lyricon.localprovider"
        minSdk = 27
        targetSdk = 36
        testInstrumentationRunner = providers.gradleProperty("localProviderTestRunner").getOrElse("androidx.test.runner.AndroidJUnitRunner")
        versionCode = 3
        versionName = "1.0.2"

        ndk {
            abiFilters.add("arm64-v8a")
            abiFilters.add("armeabi-v7a")
        }

        resConfigs("zh", "en")
    }

    signingConfigs {
        create("release") {
            val store = file(System.getenv("RELEASE_STORE_FILE") ?: "release.jks")
            if (store.exists()) {
                storeFile = store
                storePassword = System.getenv("RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            // 没有 release.jks 时退回 debug 签名，保证其他人可以直接构建
            val releaseSigning = signingConfigs.getByName("release")
            signingConfig =
                if (releaseSigning.storeFile != null) releaseSigning else signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        aidl = true
    }
}

// 可选：在 gradle.properties 或命令行用 -PlocalProviderExportDir=... 指定 APK 导出目录
val exportDir = providers.gradleProperty("localProviderExportDir").orNull
if (exportDir != null) {
    extensions.configure<com.android.build.api.variant.ApplicationAndroidComponentsExtension> {
        onVariants { variant ->
            val type = variant.buildType ?: return@onVariants
            val exportTask = tasks.register<Copy>("export${variant.name.replaceFirstChar { it.uppercase() }}ToOutput") {
                from(variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.APK))
                include("*.apk")
                into(exportDir)
                val version = variant.outputs.first().versionName.getOrElse("1.0.0")
                rename { "local-provider-$version-$type.apk" }
                outputs.upToDateWhen { false }
            }
            tasks.matching { it.name == "assemble${variant.name.replaceFirstChar { it.uppercase() }}" }
                .configureEach { finalizedBy(exportTask) }
        }
    }
}

dependencies {
    // 共享模块（依赖更新后的 lrckit）
    implementation(project(":share:lrckit"))              
    implementation(project(":share:extensions-kt"))
    implementation(project(":share:extensions-android"))

    implementation(project(":share:cloudlyric"))

    implementation(libs.lyricon.provider)
    implementation(libs.yukihookapi.api)
    implementation(libs.kavaref.core)
    implementation(libs.kavaref.extension)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.taglib)      // 内嵌歌词需要
    compileOnly(libs.xposed.api)
    ksp(libs.yukihookapi.ksp.xposed)
    testImplementation(libs.junit)
    androidTestImplementation(libs.okhttp)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
