import java.util.Properties

plugins { id("com.android.application"); kotlin("android"); kotlin("plugin.serialization"); id("org.jetbrains.kotlin.plugin.compose") }
val releaseSigning = Properties().apply {
    val signingFile=rootProject.file(providers.gradleProperty("taprunner.signingProperties").getOrElse("signing.properties"))
    if(signingFile.exists()) signingFile.inputStream().use { load(it) }
}
val signingKeys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
if (releaseSigning.isNotEmpty()) {
    require(signingKeys.all { !releaseSigning.getProperty(it).isNullOrBlank() }) {
        "发布签名配置不完整，需要 storeFile、storePassword、keyAlias、keyPassword"
    }
    require(rootProject.file(releaseSigning.getProperty("storeFile")).isFile) { "发布签名密钥文件不存在" }
}
gradle.taskGraph.whenReady {
    if (allTasks.any { it.project == project && it.name in listOf("assembleRelease", "packageRelease", "bundleRelease", "signReleaseBundle") }) {
        require(releaseSigning.isNotEmpty()) { "发布构建需要独立的 signing.properties；不会使用调试签名或输出未签名发布包" }
    }
}
android {
    namespace = "cn.taprunner"
    compileSdk = 36
    defaultConfig { applicationId = "cn.taprunner"; minSdk = 30; targetSdk = 36; versionCode = 11; versionName = "0.5.1" }
    buildFeatures { compose = true }
    signingConfigs {
        if(releaseSigning.isNotEmpty()) create("release") {
            storeFile=rootProject.file(releaseSigning.getProperty("storeFile"))
            storePassword=releaseSigning.getProperty("storePassword")
            keyAlias=releaseSigning.getProperty("keyAlias")
            keyPassword=releaseSigning.getProperty("keyPassword")
        }
    }
    buildTypes {
        getByName("debug") { isDebuggable = true }
        getByName("release") {
            isDebuggable = false
            if(releaseSigning.isNotEmpty()) signingConfig=signingConfigs.getByName("release")
            isMinifyEnabled=false
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }
}
kotlin { jvmToolchain(21) }
dependencies {
    implementation(project(":core:model")); implementation(project(":core:engine"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation(platform("androidx.compose:compose-bom:2025.03.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
}
