package com.kiss.backend.model.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import jakarta.validation.constraints.NotBlank

@Entity
@Table(name = "tag")
class Tag(
    @Column(nullable = false, unique = true)
    @field:NotBlank(message = "Tag name cannot be blank")
    var name: String
) : BaseEntity()