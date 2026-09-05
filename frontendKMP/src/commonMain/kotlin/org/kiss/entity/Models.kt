package org.kiss.entity

import kotlinx.datetime.Instant

enum class DocumentType { DOCUMENT, IMAGE, PDF }

data class PersonDto(val id: Long, val name: String, val emails: Set<String> = emptySet())

data class TagDto(val id: Long, val name: String)

data class CollectionDto(
    val id: Long,
    val name: String,
    val description: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** Option model shared by the column filter dropdowns (type / parties / tags). */
data class AutocompleteEntity(val id: Long, val name: String)

/** UI row: single document as rendered by the table + preview drawer. */
data class DocumentRowDto(
    val id: Long,
    val title: String,
    val type: DocumentType,
    val parties: List<PersonDto>,
    val tags: List<TagDto>,
    val collections: List<CollectionDto>,
    val pages: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** A job currently open on the backend (Temporal open execution). */
data class ProcessQueueRow(
    val uploadId: String,
    val title: String,
    val type: DocumentType?,
    val startedAt: Instant,
)

/** A terminal processing-record, paginated from the backend ledger. */
data class ProcessHistoryRow(
    val uploadId: String,
    val title: String,
    val type: DocumentType?,
    val stage: String,
    val errorCode: String?,
    val error: String?,
    val documentId: Long?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

// --- wire-to-UI converters -------------------------------------------------

fun org.kiss.data.PersonDto.toEntity(): PersonDto = PersonDto(id, name, emails)

fun org.kiss.data.TagDto.toEntity(): TagDto = TagDto(id, name)

fun org.kiss.data.CollectionDto.toEntity(): CollectionDto =
    CollectionDto(id, name, description, createdAt, updatedAt)

fun org.kiss.data.DocumentType.toEntity(): DocumentType = when (this) {
    org.kiss.data.DocumentType.DOCUMENT -> DocumentType.DOCUMENT
    org.kiss.data.DocumentType.IMAGE -> DocumentType.IMAGE
    org.kiss.data.DocumentType.PDF -> DocumentType.PDF
}

fun org.kiss.data.DocumentDto.toRow(): DocumentRowDto = DocumentRowDto(
    id = id,
    title = title,
    type = type.toEntity(),
    parties = parties.map { it.toEntity() },
    tags = tags.map { it.toEntity() },
    collections = collections.map { it.toEntity() },
    pages = pages,
    createdAt = createdAt,
    updatedAt = updatedAt,
)