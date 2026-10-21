// Versões fixadas no que já está em `~/.gradle/caches` nesta máquina.
// Trocar uma delas pode exigir download — e trocar AGP/Kotlin juntos exige
// atualizar também o `composeOptions` (ver app/build.gradle.kts).
plugins {
    id("com.android.application") version "8.2.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.20" apply false
    id("androidx.baselineprofile") version "1.2.4" apply false
}
