package com.kiss.backend.model.entity


import jakarta.persistence.*
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty

enum class DocumentType { DOCUMENT, IMAGE, PDF }

@Entity
@Table(name = "document")
class Document(
    @Column(nullable = false, unique = true)
    @field:NotBlank(message = "File hash is required")
    var fileHash: String,

    // Local file-store key under which the original is stored
    @Column(name = "object_key", nullable = false)
    var objectKey: String,

    // Sniffed content type (Tika) - single source of truth for download
    // Content-Type + inline/attachment disposition.
    @Column(name = "mime_type", nullable = false)
    var mimeType: String,

    // Brief description
    @Column(nullable = false)
    @field:NotBlank(message = "Title cannot be empty")
    var title: String,

    @Column(nullable = false)
    @field:Min(value = 1, message = "Pages must be at least 1")
    var pages: Int,

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
        name = "document_parties",
        joinColumns = [JoinColumn(name = "document_id")],
        inverseJoinColumns = [JoinColumn(name = "person_id")]
    )
    var parties: MutableSet<Person>,

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
        name = "document_tags",
        joinColumns = [JoinColumn(name = "document_id")],
        inverseJoinColumns = [JoinColumn(name = "tag_id")]
    )
    var tags: MutableSet<Tag>,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var type: DocumentType,

) : BaseEntity()