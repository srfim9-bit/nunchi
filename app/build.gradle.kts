plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.nunchi"
    compileSdk = 35
    // AAOS 표준 차량 속성(RANGE_REMAINING, GEAR_SELECTION) — Pleos가 권장하는 방식
    useLibrary("android.car")

    defaultConfig {
        applicationId = "app.nunchi"
        // Pleos 가이드: 차량 사이버보안(서명 키 로테이션) 요건으로 minSdk 28 이상
        minSdk = 28
        targetSdk = 35
        versionCode = 16
        versionName = "0.5.4-mvp"
    }

    buildTypes {
        release {
            // 공모전 제출용 release 빌드: 비밀 키 없이 누구나 같은 명령으로 설치 가능한 APK를 만들 수 있게 디버그 키로 서명한다.
            // (스토어 배포 때는 전용 키로 바꾼다)
            signingConfig = signingConfigs.getByName("debug")
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
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.vehicle.sdk)
    implementation(libs.pleos.tts)
    implementation(libs.pleos.stt)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
}
