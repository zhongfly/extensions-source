import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "CopyMangas"
    versionCode = 55
    contentWarning = ContentWarning.SAFE
    libVersion = "1.4"

    source {
        name = "拷贝漫画"
        lang = "zh"
        baseUrl = "https://www.copy3000.com"
    }
}

dependencies {
    implementation("com.luhuiguo:chinese-utils:1.0")
}

android {
    packaging {
        resources {
            excludes += "/pinyin.txt"
            excludes += "/polyphone.txt"
            excludes += "/trad.txt"
            excludes += "/traditional.txt"
            excludes += "/unknown.txt"
        }
    }
}
