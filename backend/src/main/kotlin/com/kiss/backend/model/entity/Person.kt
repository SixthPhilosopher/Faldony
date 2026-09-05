package com.kiss.backend.model.entity

import jakarta.persistence.*
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty

@Entity
@Table(name = "person")
class Person(
    @Column(nullable = false)
    @field:NotBlank(message = "Name cannot be blank")
    var name: String,

    @ElementCollection
    @CollectionTable(name = "person_emails", joinColumns = [JoinColumn(name = "person_id")])
    @Column(name = "email")
    @field:NotEmpty(message = "At least one email is required")
    var email: MutableSet<String>
) : BaseEntity()