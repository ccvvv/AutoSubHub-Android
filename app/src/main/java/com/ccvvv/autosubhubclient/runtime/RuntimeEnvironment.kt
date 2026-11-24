package com.ccvvv.autosubhubclient.runtime

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build

data class RuntimeEnvironmentSnapshot(
    val androidApi: Int,
    val targetSdk: Int,
    val supportedAbis: List<String>,
    val nativeLibraryDirectory: String,
    val nativeLibrariesReadable: Boolean,
    val appFilesWritable: Boolean,
    val extractNativeLibraries: Boolean,
    /** Remains false until a packaged ARM64 ELF is executed successfully on-device. */
    val elfExecutionVerified: Boolean = false
) {
    val supportsArm64: Boolean
        get() = supportedAbis.any { it == "arm64-v8a" || it == "aarch64" }

    fun summary(): String {
        val abi = supportedAbis.joinToString().ifBlank { "unknown" }
        return "Android API $androidApi · targetSdk $targetSdk · ABI: $abi\n" +
            "App files writable: $appFilesWritable · native libs readable: $nativeLibrariesReadable\n" +
            "extractNativeLibs: $extractNativeLibraries · ELF execution: " +
            if (elfExecutionVerified) "verified" else "not yet verified"
    }
}

object RuntimeEnvironment {
    fun inspect(context: Context): RuntimeEnvironmentSnapshot {
        val app = context.applicationInfo
        val nativeDir = app.nativeLibraryDir.orEmpty()
        val nativeDirectory = if (nativeDir.isBlank()) null else java.io.File(nativeDir)
        return RuntimeEnvironmentSnapshot(
            androidApi = Build.VERSION.SDK_INT,
            targetSdk = app.targetSdkVersion,
            supportedAbis = Build.SUPPORTED_ABIS?.toList().orEmpty(),
            nativeLibraryDirectory = nativeDir,
            nativeLibrariesReadable = nativeDirectory?.canRead() == true,
            appFilesWritable = context.filesDir.canWrite(),
            extractNativeLibraries = app.flags and ApplicationInfo.FLAG_EXTRACT_NATIVE_LIBS != 0
        )
    }
}
