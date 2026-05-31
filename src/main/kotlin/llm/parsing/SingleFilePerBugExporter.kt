package llm.parsing

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File

class SingleFilePerBugExporter(
    private val mapper: ObjectMapper = jacksonObjectMapper(),
) {
    fun exportBug(bug: BugsStructure, outputDir: File) {
        outputDir.mkdirs()
        val file = File(outputDir, "${bug.systemNumber}.json")
        mapper.writerWithDefaultPrettyPrinter().writeValue(file, bug)
    }
}