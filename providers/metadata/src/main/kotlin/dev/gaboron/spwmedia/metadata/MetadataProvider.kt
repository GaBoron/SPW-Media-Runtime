package dev.gaboron.spwmedia.metadata

import dev.gaboron.spwmedia.protocol.*
import dev.gaboron.spwmedia.provider.*
import kotlinx.serialization.json.*
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.audio.exceptions.CannotReadException
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.ArtworkFactory
import java.nio.file.*

/** jaudiotagger fills host API gaps; file locks cover tag and artwork read-modify-write operations. */
class MetadataProvider : MediaProvider {
    override val id = "metadata"
    override fun availability(assets: Assets) = Capability(id, available = assets.platform != "unsupported", reason = if (assets.platform == "unsupported") "UNSUPPORTED_PLATFORM" else "", features = listOf("read", "write", "artwork", "fields:" + FieldKey.entries.joinToString(",") { it.name }))
    override fun invoke(context: Invocation, operation: String, arguments: JsonObject): JsonElement {
        val path = Path.of(arguments.text("path"))
        return context.withFileLock(path) {
            if (!Files.isRegularFile(path)) throw NoSuchFileException(path.toString())
            val audio = try { AudioFileIO.read(path.toFile()) } catch (_: CannotReadException) {
                throw ProviderFailure("METADATA_READ_FAILED", "Metadata backend could not read this audio format or file; check format support and file integrity")
            }
            val tag = audio.tagOrCreateAndSetDefault
            when (operation) {
                "read" -> Unit
                "write" -> {
                    arguments["fields"]!!.jsonObject.forEach { (name, values) ->
                        val key = FieldKey.valueOf(name.uppercase())
                        val fields = values.jsonArray.map { it.jsonPrimitive.content }
                        tag.deleteField(key)
                        fields.forEach { tag.addField(key, it) }
                    }
                    context.checkCancelled(); audio.commit()
                }
                "setArtwork" -> {
                    tag.deleteArtworkField()
                    arguments["image"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content?.let { tag.setField(ArtworkFactory.createArtworkFromFile(Path.of(it).toFile())) }
                    context.checkCancelled(); audio.commit()
                }
                "extractArtwork" -> {
                    val index = arguments.number("index").toInt()
                    val artwork = tag.artworkList.getOrNull(index) ?: throw ProviderFailure("ARTWORK_NOT_FOUND", "Artwork index is absent")
                    val target = Path.of(arguments.text("output"))
                    Files.write(target, artwork.binaryData, StandardOpenOption.CREATE_NEW)
                    return@withFileLock JsonPrimitive(target.toAbsolutePath().toString())
                }
                else -> throw ProviderFailure("OPERATION_UNSUPPORTED", "Unknown metadata operation")
            }
            val values = FieldKey.entries.mapNotNull { key ->
                val list = runCatching { (0 until tag.getFields(key).size).map { tag.getValue(key, it) } }.getOrDefault(emptyList())
                if (list.isEmpty()) null else key.name to list
            }.toMap()
            Protocol.json.encodeToJsonElement(MediaTags(values, tag.artworkList.size))
        }
    }
}
