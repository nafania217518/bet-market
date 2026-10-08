package com.betting.user

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "users")
class User(
    @Id val id: UUID,
    val email: String,
    @Column(name = "password_hash") val passwordHash: String,
    @Column(name = "display_name") val displayName: String,
    // Comma-separated role names.
    val roles: String = "USER",
    @Column(name = "created_at") val createdAt: Instant = Instant.now(),
) {
    fun roleList(): List<String> = roles.split(",")
}

interface UserRepository : JpaRepository<User, UUID> {
    fun findByEmail(email: String): User?
}
