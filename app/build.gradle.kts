import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}

// 发布签名：密钥不进仓库。环境变量 OPENHC_SIGNING 或 local.properties 里的 openhc.signing 指向一份配置文件
// （storeFile、storePassword、keyAlias、keyPassword）；都没有时正式包不签名，调试包照常用调试密钥。
val signingProperties: Properties? = (
    providers.environmentVariable("OPENHC_SIGNING").orNull
        ?: localProperties.getProperty("openhc.signing")
    )
    ?.takeIf { it.isNotBlank() }
    ?.let { path -> Properties().apply { file(path).inputStream().use { load(it) } } }

// 连接参数（openhc-identity.json，格式见 docs/identity.example.json）：不进仓库，构建时从本机目录打进 assets。
// 环境变量 OPENHC_IDENTITY_DIR 优先，其次 local.properties 的 openhc.identity.dir；都没有就不打包，装上后首页提示缺少。
val identityFileName = "openhc-identity.json"
val identityDir: File? = (
    providers.environmentVariable("OPENHC_IDENTITY_DIR").orNull
        ?: localProperties.getProperty("openhc.identity.dir")
    )
    ?.takeIf { it.isNotBlank() }
    ?.let { file(it) }
    ?.also { dir ->
        val names = dir.listFiles()?.map { it.name }?.sorted()
        require(names == listOf(identityFileName)) {
            "连接参数目录 $dir 里必须只有 $identityFileName（实际：$names），防止把别的私有文件打进安装包"
        }
    }

android {
    namespace = "io.github.gcbond.openhc"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "io.github.gcbond.openhc"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        // versionCode = 主版本 × 10000 + 次版本 × 100 + 补丁号。
        versionCode = 300
        versionName = "0.3.0"
    }

    sourceSets {
        getByName("main") {
            identityDir?.let { assets.srcDir(it) }
        }
    }

    signingConfigs {
        signingProperties?.let { props ->
            create("release") {
                storeFile = file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    androidResources {
        // 仅保留中英文资源（本应用界面为中文，裁剪 AndroidX 附带的 70+ 种车机无用语言字符串表）。
        localeFilters += listOf("zh", "zh-rCN", "zh-rHK", "zh-rTW", "en", "en-rAU", "en-rCA", "en-rGB", "en-rIN")
    }

    packaging {
        resources {
            // 剔除 BouncyCastle 未使用的后量子查表资源（约 1.2 MB 压缩体积）及无用编译期元数据，保留全架构原生库与协程 SPI。
            excludes += setOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/versions/**/OSGI-INF/MANIFEST.MF",
                "org/bouncycastle/**",
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json",
                "kotlin/**/*.kotlin_builtins",
                "META-INF/*.version",
                "META-INF/com/android/build/gradle/app-metadata.properties",
                "META-INF/version-control-info.textproto",
            )
        }
    }
}

dependencies {
    implementation(project(":core:common"))
    // 会话编排：把四个协议层串成一次有线连接，执行器用 java.net，安卓上直接用。
    implementation(project(":core:session"))
    implementation(project(":platform"))
    // 连接状态用 StateFlow 交给界面。
    implementation(libs.kotlinx.coroutines.core)
    // 无线连接页显示配对二维码（Apache-2.0）。
    implementation(libs.zxing.core)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
}
