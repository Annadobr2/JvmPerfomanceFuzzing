package infrastructure.jvm

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import infrastructure.jvm.entity.JvmConfig
import java.io.File
import java.io.InputStream

/**
 * Предоставляет пути к исполняемым файлам различных JVM на основе конфигурационного файла
 * или автоматического поиска.
 */
class JvmConfigReader {

    private val objectMapper = ObjectMapper()

    fun getJvmPath(jvmType: JvmType): String {
        val config = loadConfig()
        return when (jvmType) {
            JvmType.HOT_SPOT    -> validateJvmPath(config.hotSpotJvm)   ?: findJvmPath("java-17-openjdk")
            JvmType.OPEN_J9     -> validateJvmPath(config.openJ9Jvm)    ?: findJvmPath("ibm-semeru-17")
            JvmType.GRAAL_VM    -> validateJvmPath(config.graalVmJvm)   ?: findJvmPath("graalvm-ce-java17")
            JvmType.AXIOM       -> validateJvmPath(config.axiomJvm)     ?: findJvmPath("axiom-jvm-17")
        }
    }

    /**
     * Возвращает мажорную версию Java первого сконфигурированного JVM-пути.
     * Порядок проверки: HotSpot → OpenJ9 → GraalVM → Axiom.
     * Версия извлекается из имени директории (например, `corretto-17.0.19` → 17).
     * Если извлечь не удалось — возвращается 17 как значение по умолчанию.
     */
    fun getFirstConfiguredJavaVersion(): Int {
        val config = loadConfig()
        val firstPath = listOfNotNull(
            config.hotSpotJvm, config.openJ9Jvm,
            config.graalVmJvm, config.axiomJvm,
        ).firstOrNull { !it.isNullOrBlank() } ?: return DEFAULT_JAVA_VERSION
        return extractVersionFromPath(firstPath)
    }

    /**
     * Извлекает мажорную версию Java из строки пути.
     * Ищет шаблон `-<version>` или `-<version>.` в сегментах пути,
     * где version — двух- или трёхзначное число в диапазоне [8, 999].
     * Примеры: `corretto-17.0.19` → 17, `graalvm-ce-21.0.1` → 21, `semeru-11.0.9` → 11.
     */
    private fun extractVersionFromPath(jvmPath: String): Int {
        val versionRegex = Regex("""-(\d{2,3})(?:[.\-]|$)""")
        val segments = jvmPath.replace('\\', '/').split('/')
        for (segment in segments.asReversed()) {
            val match = versionRegex.find(segment) ?: continue
            val version = match.groupValues[1].toIntOrNull() ?: continue
            if (version in 8..999) return version
        }
        println("[JvmConfigReader] Не удалось извлечь версию Java из пути '$jvmPath', используется $DEFAULT_JAVA_VERSION")
        return DEFAULT_JAVA_VERSION
    }

    private fun loadConfig(): JvmConfig {
        val resourceStream: InputStream? = this.javaClass.getResourceAsStream("/jvm_config.json")
        if (resourceStream != null) {
            return objectMapper.readValue(resourceStream)
        } else {
            throw RuntimeException("Конфиг файл не найден в ресурсе")
        }
    }

    private fun validateJvmPath(jvmPath: String?): String? {
        if (jvmPath != null && File(jvmPath).exists()) {
            return jvmPath
        }
        return null
    }

    private fun findJvmPath(jvmPrefix: String): String {
        val jvmDir = File("/usr/lib/jvm/")

        if (jvmDir.exists() && jvmDir.isDirectory) {
            val matchingJvm = jvmDir.listFiles()
                ?.filter { it.isDirectory && it.name.startsWith(jvmPrefix) }
                ?.maxByOrNull { it.name } // Берем самую новую версию

            if (matchingJvm != null) {
                val javaBinary = File(matchingJvm, "bin/java")
                if (javaBinary.exists() && javaBinary.canExecute()) {
                    return javaBinary.absolutePath
                }
            }
        }

        throw IllegalStateException("Не удалось найти JVM с префиксом: $jvmPrefix в /usr/lib/jvm/")
    }

    companion object {
        private const val DEFAULT_JAVA_VERSION = 17
    }
}
