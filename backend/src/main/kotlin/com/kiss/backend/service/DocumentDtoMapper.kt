package com.kiss.backend.service

import com.kiss.backend.model.dto.CollectionDto
import com.kiss.backend.model.dto.DocumentDto
import com.kiss.backend.model.dto.PersonDto
import com.kiss.backend.model.dto.TagDto
import com.kiss.backend.model.entity.CollectionDocumentAssociation
import com.kiss.backend.model.entity.Document

/**
 * Shared document -> DTO mapping (DocumentService and CollectionController both
 * use it, so the API shape lives in exactly one place).
 *
 * Note: `mimeType` is intentionally NOT exposed — it is server-internal
 * storage metadata (see the DTO decision that dropped it).
 */
object DocumentDtoMapper {

    /**
     * [emailsByPersonId] is passed in (never loaded lazily here) so the
     * listing paths can resolve all emails with a single batched query
     * instead of N+1 per party row.
     */
    fun toDto(
        doc: Document,
        associations: List<CollectionDocumentAssociation> = emptyList(),
        emailsByPersonId: Map<Long, Set<String>> = emptyMap()
    ) =
        DocumentDto(
            id = doc.id!!,
            title = doc.title,
            type = doc.type,
            parties = doc.parties.map { p ->
                PersonDto(id = p.id!!, name = p.name, emails = emailsByPersonId[p.id] ?: emptySet())
            },
            tags = doc.tags.map { t -> TagDto(id = t.id!!, name = t.name) },
            collections = associations.map { it.collection.toCollectionDto() },
            pages = doc.pages,
            createdAt = doc.createdAt,
            updatedAt = doc.updatedAt
        )

    private fun com.kiss.backend.model.entity.Collection.toCollectionDto() = CollectionDto(
        id = id!!,
        name = name,
        description = description,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}