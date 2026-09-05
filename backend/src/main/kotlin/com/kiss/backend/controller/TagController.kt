package com.kiss.backend.controller

import com.kiss.backend.model.dto.*
import com.kiss.backend.model.entity.Tag
import com.kiss.backend.repository.TagRepository
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import io.swagger.v3.oas.annotations.tags.Tag as SwaggerTag

@RestController
@RequestMapping("/api/v1/tags")
@SwaggerTag(name = "Tags", description = "Tag management endpoints")
class TagController(
    private val tagRepository: TagRepository
) {

    /** Full list (reference data — plain, unbounded; used for dropdowns too). */
    @GetMapping
    @Operation(summary = "List all tags")
    @ApiResponse(responseCode = "200", description = "List of tags")
    fun getTags(): List<TagDto> =
        tagRepository.findAll().map { toDto(it) }

    @GetMapping("/{id}")
    @Operation(summary = "Get tag by ID")
    @ApiResponse(responseCode = "200", description = "Tag details")
    @ApiResponse(
        responseCode = "404",
        description = "Tag not found",
        content = [Content(schema = Schema(implementation = ApiError::class))]
    )
    fun getTagById(@PathVariable id: Long): ResponseEntity<TagDto> {
        val tag = tagRepository.findById(id)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Tag not found") }
        return ResponseEntity.ok(toDto(tag))
    }

    @PostMapping
    @Operation(summary = "Create tag")
    @ApiResponse(responseCode = "201", description = "Tag created")
    fun createTag(@Valid @RequestBody req: TagCreateRequest): ResponseEntity<TagDto> {
        val tag = Tag(name = req.name)
        val saved = tagRepository.save(tag)
        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(saved))
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Update tag")
    @ApiResponse(responseCode = "200", description = "Tag updated")
    @ApiResponse(
        responseCode = "404",
        description = "Tag not found",
        content = [Content(schema = Schema(implementation = ApiError::class))]
    )
    fun patchTag(
        @PathVariable id: Long,
        @Valid @RequestBody req: TagPatchRequest
    ): ResponseEntity<TagDto> {
        val tag = tagRepository.findById(id)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Tag not found") }

        req.name?.let { tag.name = it }

        val saved = tagRepository.save(tag)
        return ResponseEntity.ok(toDto(saved))
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete tag")
    @ApiResponse(responseCode = "204", description = "Tag deleted")
    @ApiResponse(
        responseCode = "404",
        description = "Tag not found",
        content = [Content(schema = Schema(implementation = ApiError::class))]
    )
    fun deleteTag(@PathVariable id: Long): ResponseEntity<Void> {
        val tag = tagRepository.findById(id)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Tag not found") }
        tagRepository.delete(tag)
        return ResponseEntity.noContent().build()
    }

    private fun toDto(tag: Tag) = TagDto(
        id = tag.id!!,
        name = tag.name
    )
}