package com.kiss.backend.repository

import com.kiss.backend.model.entity.Collection
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface CollectionRepository : JpaRepository<Collection, Long> {
    fun findByName(name: String): Collection?
}
