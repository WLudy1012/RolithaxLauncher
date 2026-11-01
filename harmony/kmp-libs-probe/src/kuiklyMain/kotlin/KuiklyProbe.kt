import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

fun kuiklyDependenciesProbe(payload: String): Int = runBlocking {
    Json.parseToJsonElement(payload).jsonObject.size
}
