plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

import java.util.Properties

android {
    namespace = "com.kavelipay.app"
    compileSdk = 34

    // Carrega keystore.properties para signing
    val keystoreProps = Properties().apply {
        file("../keystore.properties").inputStream().use { load(it) }
    }

    signingConfigs {
        create("release") {
            storeFile = file(keystoreProps["storeFile"] as String)
            storePassword = keystoreProps["storePassword"] as String
            keyAlias = keystoreProps["keyAlias"] as String
            keyPassword = keystoreProps["keyPassword"] as String
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
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            applicationIdSuffix = ".debug"
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
    implementation("androidx.core:core-ktx:1.12.0")
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

    // QR Code. Só o núcleo de codificação (`core`) — o app não decodifica QR,
    // então `javase` (que puxa ImageIO e swing) seria peso morto.
    // Não usar biblioteca de PIX/BR Code aqui: o payload do KaveliPay é um
    // deep link `kavelipay://`, e nada no app processa pagamento.
    implementation("com.google.zxing:core:3.5.3")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // Testes de regra pura (JUnit4). Só isto: a verificação de tela continua
    // sendo build + Waydroid (tests_android.sh), que é o padrão do repositório.
    // `assembleRelease` não resolve esta config — build offline não quebra.
    testImplementation("junit:junit:4.13.2")
}
