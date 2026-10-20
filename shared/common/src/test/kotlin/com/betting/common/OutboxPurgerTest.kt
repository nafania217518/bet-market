package com.betting.common

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest
@Import(Containers::class)
class OutboxPurgerTest {
    @Autowired lateinit var jdbc: JdbcClient

    private val retention = Duration.ofDays(7)

    private fun insert(age: Duration, published: Boolean): UUID {
        val id = UUID.randomUUID()
        val createdAt = OffsetDateTime.ofInstant(Instant.now().minus(age), ZoneOffset.UTC)
        jdbc.sql("INSERT INTO outbox_event(id, aggregate_id, event_type, payload, created_at, published_at) VALUES (?, 'a', 't', '{}'::jsonb, ?, ?)")
            .params(listOf(id, createdAt, if (published) createdAt else null))
            .update()
        return id
    }

    private fun exists(id: UUID) =
        jdbc.sql("SELECT count(*) FROM outbox_event WHERE id = ?").param(id).query(Long::class.java).single() > 0

    @Test
    fun `the purge job deletes rows older than the retention and keeps newer ones`() {
        val old = insert(Duration.ofDays(8), published = true)
        val recent = insert(Duration.ofDays(1), published = true)
        assertTrue(OutboxPurger(jdbc, "poller", retention).purge() >= 1)
        assertFalse(exists(old))
        assertTrue(exists(recent))
    }

    @Test
    fun `in poller mode the purge job keeps unpublished rows`() {
        val unpublished = insert(Duration.ofDays(8), published = false)
        val published = insert(Duration.ofDays(8), published = true)
        OutboxPurger(jdbc, "poller", retention).purge()
        assertTrue(exists(unpublished))
        assertFalse(exists(published))
    }

    @Test
    fun `in debezium mode the purge job deletes old rows without published_at`() {
        val unpublished = insert(Duration.ofDays(8), published = false)
        OutboxPurger(jdbc, "debezium", retention).purge()
        assertFalse(exists(unpublished))
    }
}
