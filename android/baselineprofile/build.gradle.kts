// Módulo de MEDIÇÃO do perfil de arranque (Baseline Profile).
//
// Não entra no APK: é um módulo de teste de instrumentação que roda o app
// num aparelho, grava quais classes/métodos realmente executam durante o
// arranque e devolve isso como `baseline-prof.txt`. O AGP junta esse arquivo
// ao APK de release e o `profileinstaller` o instala no primeiro uso.
//
// Sem isso, o ART compila o app no modo "interpretado/JIT" na primeira
// abertura — em celular fraco é a diferença entre abrir em 1,2 s e 0,7 s.
plugins {
    id("com.android.test")
    // Sem este plugin o módulo publica a variante como "runtime" e o app
    // recusa a dependência: "the consumer needed a component for use during
    // 'baselineProfile'". É ele que marca a variante como produtora de perfil.
    id("androidx.baselineprofile")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kavelipay.baselineprofile"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        targetSdk = 34
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // O módulo testa o app de verdade (release, não debug): perfil medido em
    // build de debug não vale, porque o R8 muda o bytecode.
    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true

    buildTypes {
        create("benchmark") {
            isDebuggable = false
            matchingFallbacks += listOf("release")
        }
    }
}

dependencies {
    implementation("androidx.test.ext:junit:1.1.5")
    implementation("androidx.test.uiautomator:uiautomator:2.2.0")
    implementation("androidx.benchmark:benchmark-macro-junit4:1.2.2")
}
