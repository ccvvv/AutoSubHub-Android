plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ccvvv.autosubhubclient"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.ccvvv.autosubhubclient.newapp"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            abiFilters += "arm64-v8a"
        }

    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf("OSGI-INF/l10n/plugin.properties")
            pickFirsts += setOf("META-INF/DEPENDENCIES")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = true
    }
}

dependencies {
    implementation("org.eclipse.jgit:org.eclipse.jgit:7.2.0.202503040940-r")
    implementation("org.eclipse.jgit:org.eclipse.jgit.ssh.apache:7.2.0.202503040940-r") {
        // The OSGi bundle embeds the same SSHD classes as core/common and causes
        // duplicate classes in Android packaging. Use the modular runtime instead.
        exclude(group = "org.apache.sshd", module = "sshd-osgi")
    }
    implementation("org.apache.sshd:sshd-core:2.15.0")
    implementation("org.apache.sshd:sshd-sftp:2.15.0")
    implementation("org.yaml:snakeyaml:2.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
