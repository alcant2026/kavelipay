plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("androidx.baselineprofile")
}

import java.util.Properties

// Grava o perfil medido em `app/src/main/baseline-prof.txt`, para que o
// repositório carregue o resultado pronto e o build seguinte não precise de
// aparelho conectado.
baselineProfile {
    saveInSrc = true
    mergeIntoMain = true
}

android {
    namespace = "com.kavelipay.app"
    compileSdk = 34

    // Carrega keystore.properties para signing.
    //
    // O arquivo é OPCIONAL de propósito: ele é segredo e não está no git.
    // Sem esta guarda o projeto nem configurava fora desta máquina — o Gradle
    // quebrava ao ler o arquivo ausente, e nenhum build rodava. Quem não tem a
    // chave compila igual, e o release sai assinado com a chave de debug.
    val arquivoKeystore = file("../keystore.properties")
    val keystoreProps = Properties().apply {
        if (arquivoKeystore.exists()) arquivoKeystore.inputStream().use { load(it) }
    }

    signingConfigs {
        if (arquivoKeystore.exists()) {
            create("release") {
                storeFile = file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }

    defaultConfig {
        applicationId = "com.kavelipay.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        // Define o nome base do APK (sem sufixos automáticos)
        setProperty("archivesBaseName", "KaveliPay")
    }

    buildTypes {
        release {
            // Quem mede o Baseline Profile é o módulo :baselineprofile, que só
            // existe na variante `benchmark`. Sem esta linha o Gradle recusa a
            // dependência com "No matching variant of project :baselineprofile".
            matchingFallbacks += listOf("benchmark")
            isMinifyEnabled = true
            // Sem isto o R8 encolhe o código mas todos os recursos (layouts,
            // drawables, textos) continuam no APK mesmo sem referência.
            isShrinkResources = true
            // Sem a keystore de release (CI, outra máquina), o release sai
            // assinado com a chave de debug: o APK instala e roda igual, só
            // não serve para publicar na Play Store.
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            applicationIdSuffix = ".debug"
        }

        // Variante que o módulo :baselineprofile instala para MEDIR o perfil.
        // Precisa ser igual ao release (R8 muda o bytecode, e perfil medido em
        // debug não vale), mas assinada com a chave de debug — o aparelho de
        // teste não tem a keystore de release. Sem este buildType o plugin não
        // acha variante compatível: "No matching variant of project
        // :baselineprofile was found".
        create("benchmark") {
            initWith(buildTypes.getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            isDebuggable = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    // O compilador Compose é versionado junto com o Kotlin, não com o
    // material3. 1.5.4 é o par de Kotlin 1.9.20.
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.4"
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    // Quem MEDE o perfil é o módulo :baselineprofile (instrumentação no
    // aparelho). Sem esta linha o plugin aplicado acima não tem produtor:
    // `mergeBaselineProfile` falha com "no dependency has been set".
    baselineProfile(project(":baselineprofile"))
    implementation("androidx.core:core-ktx:1.12.0")
    // Instala o perfil de arranque no primeiro uso do app (Android 7+).
    implementation("androidx.profileinstaller:profileinstaller:1.3.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    // Compose
    implementation("androidx.compose.runtime:runtime:1.6.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3:1.2.0")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Rede: OkHttp direto, sem Retrofit. org.json (do próprio SDK) faz o parse —
    // menos dependências, menos superfície, e o formato da API é pequeno.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // Tokens cifrados (SessionStore): EncryptedSharedPreferences + MasterKey.
    // 1.1.0-alpha06 é a versão mais recente da lib (não há estável ainda).
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // QR Code. Só o núcleo de codificação (`core`) — o app não decodifica QR,
    // então `javase` (que puxa ImageIO e swing) seria peso morto.
    // Não usar biblioteca de PIX/BR Code aqui: o payload do KaveliPay é um
    // deep link `kavelipay://`, e nada no app processa pagamento.
    implementation("com.google.zxing:core:3.5.3")

    // Leitor de código de barras do boleto (Nova conta). Preview + análise de
    // frames; a decodificação é do zxing core que já estava aqui.
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // Testes de regra pura (JUnit4). Só isto: a verificação de tela continua
    // sendo build + Waydroid (tests_android.sh), que é o padrão do repositório.
    // `assembleRelease` não resolve esta config — build offline não quebra.
    testImplementation("junit:junit:4.13.2")
}
