package com.kiss.backend.repository

import com.kiss.backend.model.entity.Tag
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface TagRepository : JpaRepository<Tag, Long> {
    fun findByName(name: String): Tag?
}