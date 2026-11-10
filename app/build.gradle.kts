import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// CMake: o Gradle exige a versao exata. Ordem: "cmake.dir" do local.properties;
// senao o cmake do PATH (o do sistema, ex. /usr/bin/cmake), cuja versao e lida do
// proprio binario; senao o 3.31.6 do SDK Android (SDK Manager > CMake).
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val cmakeDir: String? = localProperties.getProperty("cmake.dir")
fun cmakeVersionOf(binary: String): String? = try {
    val output = providers.exec {
        commandLine(binary, "--version")
        isIgnoreExitValue = true
    }.standardOutput.asText.get()
    Regex("""cmake version (\d+\.\d+\.\d+)""").find(output)?.groupValues?.get(1)
} catch (e: Exception) {
    null
}
val cmakeVersionFromDir: String? =
    if (cmakeDir != null) cmakeVersionOf("$cmakeDir/bin/cmake") else cmakeVersionOf("cmake")

// "./gradlew assembleRelease -Pdf.uiOnly=true" compila so o launcher (Kotlin),
// sem o jogo nativo: util para mexer na interface sem esperar pelo NDK.
// O APK resultante abre o launcher mas nao consegue arrancar o jogo.
val uiOnly = (project.findProperty("df.uiOnly") as String?) == "true"
// "-Pdf.d3dTrace=true": compila os hooks de rastreio das funcoes D3D
// (app/src/main/cpp/d3d_trace_hooks.cpp, gerado por tools/d3d_trace_gen.py).
// So para mapear a biblioteca D3D; nao usar em testes de desempenho.
val d3dTrace = (project.findProperty("df.d3dTrace") as String?) == "true"
// APK universal: o nativo vai em dois conjuntos, ARMv8.2 (LSE em linha; o externalNativeBuild normal) e ARMv8.0
// para CPUs sem LSE (Snapdragon 665 e outros; tarefa legacyNative, mais abaixo, com as .so em "_v80"). O GameLib.kt
// escolhe pelo HWCAP do CPU. "-Pdb.noLegacy=true" salta o conjunto ARMv8.0 (iteracao mais rapida; o APK resultante
// nao abre o jogo em CPUs sem LSE).
val buildLegacy = !uiOnly && (project.findProperty("db.noLegacy") as String?) != "true"

android {
    namespace = "com.dfdx047.dragonburst"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.dfdx047.dragonburst"
        // API 30 (Android 11): memfd, posix_spawn, strtod_l e ASharedMemory
        // estao todos disponiveis no bionic.
        minSdk = 30
        targetSdk = 35
        versionCode = 6
        versionName = "0.2.2"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        if (!uiOnly) {
            externalNativeBuild {
                cmake {
                    arguments += listOf(
                        "-DANDROID_STL=c++_shared",
                        // O runtime e sempre compilado em Release: em Debug o jogo
                        // recompilado fica lento demais para testar.
                        "-DCMAKE_BUILD_TYPE=Release",
                        // CMake 4 recusa cmake_minimum_required < 3.5 (submodulos antigos).
                        "-DCMAKE_POLICY_VERSION_MINIMUM=3.5",
                        "-DREXGLUE_ENABLE_TRACY=OFF",
                        "-DREXGLUE_BUILD_TESTS=OFF",
                        "-DDB_D3D_TRACE=" + (if (d3dTrace) "ON" else "OFF"),
                        "-DDB_LEGACY_ARMV8=OFF",
                    )
                    // Frame pointers em todo o codigo nativo: o vigia (watchdog.cpp) e o
                    // simpleperf seguem a cadeia de frames para mostrar as pilhas.
                    cFlags += "-fno-omit-frame-pointer"
                    cppFlags += "-fno-omit-frame-pointer"
                    targets += listOf(
                        "main", "rexruntime", "rexgpu-xenos", "SDL3-shared",
                        // Hooks do libadrenotools (driver Vulkan alternativo, ex. Turnip).
                        "main_hook", "file_redirect_hook", "gsl_alloc_hook", "hook_impl",
                    )
                }
            }
        }
    }

    if (!uiOnly) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = cmakeVersionFromDir ?: "3.31.6"
            }
        }
    }

    sourceSets {
        getByName("main") {
            // Classes Java do SDL (SDLActivity e companhia), direto do submodulo
            // do SDK, para ficarem sempre na mesma versao que o libSDL3.so.
            java.srcDir("../external/burstlimit/thirdparty/rexglue-sdk/thirdparty/sdl3/android-project/app/src/main/java")
            // libgame.zdat + libgame.json: o codigo do jogo encriptado (tools/pack-libgame.py, chamado pelo CMake).
            assets.srcDir("build/dragonburst-assets")
            // librexruntime_v80.so e librexgpu-xenos_v80.so do conjunto ARMv8.0 (tarefa legacyNative).
            if (buildLegacy) jniLibs.srcDir("build/legacy-jni")
        }
    }

    packaging {
        jniLibs {
            // As .so ficam extraidas em nativeLibraryDir: o SDL abre libmain.so
            // por caminho e o runtime carrega o plugin da GPU de la.
            useLegacyPackaging = true
            // O codigo do jogo so vai encriptado (assets/libgame.zdat), nunca como .so.
            excludes += "**/libgame.so"
        }
    }

    // Chave de release: keystore.properties na raiz do projeto (fora do git) com
    // storeFile, storePassword, keyAlias, keyPassword. Sem esse arquivo, usa a chave de debug.
    val releaseKeyFile = rootProject.file("keystore.properties")
    if (releaseKeyFile.exists()) {
        val keyProps = Properties().apply { releaseKeyFile.inputStream().use { load(it) } }
        signingConfigs.create("release") {
            storeFile = rootProject.file(keyProps.getProperty("storeFile"))
            storePassword = keyProps.getProperty("storePassword")
            keyAlias = keyProps.getProperty("keyAlias")
            keyPassword = keyProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = if (releaseKeyFile.exists()) signingConfigs.getByName("release")
                else signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

// Conjunto ARMv8.0 do APK universal: o mesmo CMakeLists, com DB_LEGACY_ARMV8=ON e DB_LIB_SUFFIX=_v80, numa pasta
// propria (.cxx-legacy). Escreve libgame_v80.zdat/json em build/dragonburst-assets; das .so so as "_v80" entram no
// APK (build/legacy-jni): o libSDL3.so, o libmain.so e os hooks sao os do conjunto ARMv8.2, ja compilados em ARMv8.0.
val legacyCxxDir = layout.projectDirectory.dir(".cxx-legacy/Release/arm64-v8a")
val legacyObjDir = layout.buildDirectory.dir("intermediates/cxx-legacy/Release/obj/arm64-v8a")
val legacyConfigure = tasks.register<Exec>("legacyNativeConfigure") {
    val ndk = android.ndkDirectory.absolutePath
    val cmakeBin = if (cmakeDir != null) "$cmakeDir/bin/cmake" else "cmake"
    val objDir = legacyObjDir.get().asFile.absolutePath
    val args = mutableListOf(
        cmakeBin,
        "-H" + file("src/main/cpp/CMakeLists.txt").parentFile.absolutePath,
        "-B" + legacyCxxDir.asFile.absolutePath,
        "-GNinja",
        "-DCMAKE_SYSTEM_NAME=Android",
        "-DCMAKE_SYSTEM_VERSION=30",
        "-DANDROID_PLATFORM=android-30",
        "-DANDROID_ABI=arm64-v8a",
        "-DCMAKE_ANDROID_ARCH_ABI=arm64-v8a",
        "-DANDROID_NDK=$ndk",
        "-DCMAKE_ANDROID_NDK=$ndk",
        "-DCMAKE_TOOLCHAIN_FILE=$ndk/build/cmake/android.toolchain.cmake",
        "-DCMAKE_C_FLAGS=-fno-omit-frame-pointer",
        "-DCMAKE_CXX_FLAGS=-fno-omit-frame-pointer",
        "-DCMAKE_LIBRARY_OUTPUT_DIRECTORY=$objDir",
        "-DCMAKE_RUNTIME_OUTPUT_DIRECTORY=$objDir",
        "-DANDROID_STL=c++_shared",
        "-DCMAKE_BUILD_TYPE=Release",
        "-DCMAKE_POLICY_VERSION_MINIMUM=3.5",
        "-DREXGLUE_ENABLE_TRACY=OFF",
        "-DREXGLUE_BUILD_TESTS=OFF",
        "-DDB_D3D_TRACE=" + (if (d3dTrace) "ON" else "OFF"),
        "-DDB_LEGACY_ARMV8=ON",
        "-DDB_LIB_SUFFIX=_v80",
    )
    if (cmakeDir != null) args += "-DCMAKE_MAKE_PROGRAM=$cmakeDir/bin/ninja"
    commandLine(args)
}
val legacyBuild = tasks.register<Exec>("legacyNativeBuild") {
    dependsOn(legacyConfigure)
    val cmakeBin = if (cmakeDir != null) "$cmakeDir/bin/cmake" else "cmake"
    commandLine(cmakeBin, "--build", legacyCxxDir.asFile.absolutePath,
        "--target", "game_pack", "rexruntime", "rexgpu-xenos")
}
val legacyNative = tasks.register<Sync>("legacyNative") {
    dependsOn(legacyBuild)
    from(legacyObjDir) { include("*_v80.so") }
    // O SDK fixa a saida do librexruntime em out/<plataforma> (o plugin da GPU e copiado para junto do libgame.so,
    // o runtime nao): sem esta linha o APK ia sem librexruntime_v80.so e o jogo nao abria nos CPUs ARMv8.0.
    from(rootProject.file("external/burstlimit/thirdparty/rexglue-sdk/out/linux-arm64")) {
        include("librexruntime_v80.so")
    }
    into(layout.buildDirectory.dir("legacy-jni/arm64-v8a"))
}

// Os assets juntam-se depois do CMake (que escreve o libgame.zdat; o legacyNative escreve o libgame_v80.zdat), e as
// .so do conjunto ARMv8.0 antes de se juntarem as bibliotecas nativas.
tasks.configureEach {
    val m = Regex("merge(\\w+)Assets").matchEntire(name)
    if (m != null && !uiOnly) dependsOn("externalNativeBuild" + m.groupValues[1])
    if (buildLegacy && (m != null || Regex("merge\\w+(JniLibFolders|NativeLibs)").matches(name))) dependsOn(legacyNative)
}
