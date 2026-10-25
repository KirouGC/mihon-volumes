package mihon.feature.volumes

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File

/**
 * Keeps the global `volume_mappings.json` in the app's internal storage: the content format of each manga and the
 * chapters of its volumes.
 */
@Inject
@SingleIn(AppScope::class)
class VolumeMappingStore(
    context: Context,
    private val json: Json,
) {

    private val file = File(context.filesDir, FILE_NAME)

    private val writeMutex = Mutex()

    private val mappings = MutableStateFlow(read())

    val changes: StateFlow<VolumeMappings> = mappings.asStateFlow()

    fun get(sourceId: Long, url: String): MangaVolumeMapping? {
        return mappings.value.mangas[mappingKey(sourceId, url)]
    }

    suspend fun set(sourceId: Long, url: String, mapping: MangaVolumeMapping) {
        writeMutex.withLock {
            mappings.update { it.copy(mangas = it.mangas + (mappingKey(sourceId, url) to mapping)) }
            write(mappings.value)
        }
    }

    private fun read(): VolumeMappings {
        if (!file.exists()) return VolumeMappings()
        return try {
            json.decodeFromString(VolumeMappings.serializer(), file.readText())
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to read $FILE_NAME" }
            VolumeMappings()
        }
    }

    private suspend fun write(content: VolumeMappings) = withContext(Dispatchers.IO) {
        try {
            val temp = File(file.parentFile, "$FILE_NAME.tmp")
            temp.writeText(json.encodeToString(VolumeMappings.serializer(), content))
            if (!temp.renameTo(file)) {
                file.writeText(temp.readText())
                temp.delete()
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to write $FILE_NAME" }
        }
    }

    companion object {
        const val FILE_NAME = "volume_mappings.json"
    }
}
