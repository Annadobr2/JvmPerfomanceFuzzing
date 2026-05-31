package core.seed

import java.io.File


/**
 * Представляет исходный код Java-класса.
 */
class JavaCode (val code: String) {

    companion object {

        fun load(seedsDir: String, classNames: List<String>): List<String> {
            return classNames.mapNotNull { className ->
                val file = File("$seedsDir/$className.java")

                if (!file.exists() || !file.isFile) {
                    null
                } else {
                    file.readText()
                }
            }
        }


    }


}