pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/public")
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        google()
        mavenCentral()
    }
}

// Optional local redirection keeps duplicate checkouts on a single source tree.
// Without this local property, a normal Git clone builds its own sources.
val localConfig = java.util.Properties().apply {
    val localFile = settingsDir.resolve("local.properties")
    if (localFile.isFile) localFile.inputStream().use { load(it) }
}
val canonicalPath = localConfig.getProperty("canonical.project.path")?.trim()
val sourceRoot = if (canonicalPath.isNullOrEmpty()) settingsDir else file(canonicalPath).canonicalFile
check(sourceRoot.resolve("app/build.gradle.kts").isFile) {
    "Configured source project is missing: $sourceRoot. Check canonical.project.path in local.properties."
}
rootProject.name = "MyDiary"
include(":app")
project(":app").projectDir = sourceRoot.resolve("app")
include(":Framework:framework")
project(":Framework:framework").projectDir = sourceRoot.resolve("CubismSdkForJava-5-r.4.1/Framework/framework")
println("MoodDairy source project: ${sourceRoot.canonicalPath}")
