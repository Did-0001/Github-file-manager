package com.example.data.remote.dto

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter

/**
 * Custom JsonAdapter for CreateTreeEntryDto to guarantee that when [CreateTreeEntryDto.sha]
 * is null (used for batch deletion in Git Data API), the JSON serializer explicitly
 * writes `"sha": null` rather than omitting the field.
 */
class CreateTreeEntryJsonAdapter : JsonAdapter<CreateTreeEntryDto>() {

    override fun fromJson(reader: JsonReader): CreateTreeEntryDto {
        var path = ""
        var mode = "100644"
        var type = "blob"
        var sha: String? = null

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "path" -> path = reader.nextString()
                "mode" -> mode = reader.nextString()
                "type" -> type = reader.nextString()
                "sha" -> {
                    if (reader.peek() == JsonReader.Token.NULL) {
                        reader.nextNull<Unit>()
                        sha = null
                    } else {
                        sha = reader.nextString()
                    }
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return CreateTreeEntryDto(path = path, mode = mode, type = type, sha = sha)
    }

    override fun toJson(writer: JsonWriter, value: CreateTreeEntryDto?) {
        if (value == null) {
            writer.nullValue()
            return
        }
        val prevSerializeNulls = writer.serializeNulls
        writer.serializeNulls = true
        try {
            writer.beginObject()
            writer.name("path").value(value.path)
            writer.name("mode").value(value.mode)
            writer.name("type").value(value.type)
            writer.name("sha")
            if (value.sha == null) {
                writer.nullValue()
            } else {
                writer.value(value.sha)
            }
            writer.endObject()
        } finally {
            writer.serializeNulls = prevSerializeNulls
        }
    }
}
