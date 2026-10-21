package dev.gaboron.spwmedia.sdk

import dev.gaboron.spwmedia.protocol.*
import kotlinx.serialization.json.*
import java.nio.file.Path

/** File metadata and artwork. Pass values already available from the host instead of rereading them. */
class Metadata internal constructor(private val client: MediaRuntime) {
    /** Read supported audio tags; use the host Library API first for library information/cover reads. */
    fun read(path: Path): RuntimeTask<MediaTags> = client.task("metadata", "read", obj("path" to path.toString())) { Protocol.json.decodeFromJsonElement(it) }
    /** Replace specified tag fields; empty lists remove the field. Unspecified fields remain unchanged. */
    fun write(path: Path, fields: Map<String, List<String>>): RuntimeTask<MediaTags> = client.task("metadata", "write", obj("path" to path.toString(), "fields" to fields)) { Protocol.json.decodeFromJsonElement(it) }
    /** Extract artwork into an explicit caller-owned output file. Existing output is not overwritten. */
    fun extractArtwork(path: Path, output: Path, index: Int = 0): RuntimeTask<String> = client.task("metadata", "extractArtwork", obj("path" to path.toString(), "output" to output.toString(), "index" to index)) { it.jsonPrimitive.content }
    /** Replace embedded artwork from an image file; null removes all artwork. */
    fun setArtwork(path: Path, image: Path?): RuntimeTask<MediaTags> = client.task("metadata", "setArtwork", obj("path" to path.toString(), "image" to image?.toString())) { Protocol.json.decodeFromJsonElement(it) }
}
