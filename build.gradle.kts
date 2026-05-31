plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("kapt") version "2.0.21"
    java
    application
}

group = "JvmPerfomanceFuzzing"
version = "1.0"

application {
    mainClass.set("MainKt")
}

repositories {
    mavenCentral()
    google()
}

dependencies {
    testImplementation(kotlin("test"))
    implementation(kotlin("stdlib-jdk8"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.0")

    implementation("org.openjdk.jmh:jmh-core:1.37")
    kapt("org.openjdk.jmh:jmh-generator-annprocess:1.37")

    // ASM
    implementation("org.ow2.asm:asm:9.5")
    implementation("org.ow2.asm:asm-commons:9.5")
    implementation("org.ow2.asm:asm-tree:9.5")
    implementation("org.ow2.asm:asm-analysis:9.5")
    implementation("org.ow2.asm:asm-util:9.5")

    // Sooutup
    implementation("org.soot-oss:sootup.core:1.3.0")
    implementation("org.soot-oss:sootup.core:1.3.0")
    implementation("org.soot-oss:sootup.java.core:1.3.0")
    implementation("org.soot-oss:sootup.jimple.parser:1.3.0")
    implementation("org.soot-oss:sootup.java.bytecode:1.3.0")

    implementation("org.soot-oss:soot:4.6.0")

    implementation("org.jetbrains.kotlinx:kotlinx-cli:0.3.6")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

tasks.withType<JavaExec> {
    jvmArgs("-Dfile.encoding=UTF-8")
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(17)
}

kapt {
    javacOptions {
        option("--release", "17")
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions {
        jvmTarget = "17"
    }
}

tasks.withType<JavaCompile> {
    sourceCompatibility = "17"
    targetCompatibility = "17"
}

tasks.jar {
    manifest {
        attributes(
            "Main-Class" to "MainKt"
        )
    }

    // Исключаем файлы подписей при создании fat JAR
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.MF")

    from({
        configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map {
            zipTree(it).matching {
                // Исключаем файлы подписей из зависимостей
                exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.MF")
            }
        }
    })

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.register("buildDockerImage") {
    group = "docker"
    description = "Собирает Docker-образ для фаззинга."

    doLast {
        exec {
            commandLine("docker", "build", "-t", "fuzzer-framework", ".")
        }
    }
}

tasks.register("runDockerContainer") {
    group = "docker"
    description = "Запускает Docker-контейнер с маунтом папки для аномалий и конфигурацией из .env."

    doLast {
        val anomaliesDir    = "${projectDir}/anomalies"
        val dataDir         = "${projectDir}/data"
        val generatedDir    = "${projectDir}/src/test/resources/GeneratedFromBugs"
        val containerName   = "fuzzer-container"
        val envFile         = file("${projectDir}/.env")

        // Создаём локальные директории, чтобы Docker не монтировал несуществующие пути
        mkdir(anomaliesDir)
        mkdir("$dataDir/openjdk_bugs")
        mkdir(generatedDir)

        if (!envFile.exists()) {
            logger.warn("[Docker] Файл .env не найден. Скопируйте .env.example → .env и заполните значения.")
        }

        val cmd = mutableListOf(
            "docker", "run", "--rm", "-it",
            "-v", "$anomaliesDir:/app/anomalies",
            "-v", "$dataDir:/app/data",
            "-v", "$generatedDir:/app/src/test/resources/GeneratedFromBugs",
            "--name", containerName,
        )

        // прокинуть .env если он существует, иначе переменные окружения
        if (envFile.exists()) {
            cmd += listOf("--env-file", envFile.absolutePath)
        } else {
            listOf("LLM_PROVIDER", "LLM_API_KEY", "LLM_BASE_URL", "LLM_MODEL").forEach { key ->
                System.getenv(key)?.let { cmd += listOf("-e", "$key=$it") }
            }
        }

        cmd += "fuzzer-framework"

        exec { commandLine(cmd) }
    }
}

tasks.register("stopDockerContainer") {
    group = "docker"
    description = "Останавливает Docker-контейнер с фаззером."

    doLast {
        exec {
            commandLine("docker", "stop", "fuzzer-container")
        }
    }
}
