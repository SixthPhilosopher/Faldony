package com.kiss.backend.repository

import com.kiss.backend.model.entity.Person
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.Optional

@Repository
interface PersonRepository : JpaRepository<Person, Long> {
    fun findByName(name: String): Person?

    /**
     * Fetches the `@ElementCollection` emails eagerly in ONE query — the
     * documents listing maps every party to its emails, and lazy loading
     * there would fan out into N+1 queries per row.
     */
    @EntityGraph(attributePaths = ["email"])
    fun findAllByIdIn(ids: Collection<Long>): List<Person>

    /**
     * Eager emails for the parties endpoints: `toDto` reads `person.email`
     * after the session is gone, so lazy loading throws
     * `LazyInitializationException` during serialization.
     */
    @EntityGraph(attributePaths = ["email"])
    override fun findAll(): List<Person>

    @EntityGraph(attributePaths = ["email"])
    override fun findById(id: Long): Optional<Person>
}