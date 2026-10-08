package com.betting.market

import com.betting.events.EventJson
import com.betting.events.EventTypes
import com.betting.events.MarketV1
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.post
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class MarketApiTest : IntegrationTest() {
    @Autowired lateinit var kafka: KafkaConnectionDetails

    private fun badRequest(r: org.springframework.test.web.servlet.ResultActionsDsl) =
        r.andExpect { status { isBadRequest() }; jsonPath("$.code") { value("VALIDATION_ERROR") } }

    @Test
    fun `create market returns 201 with status OPEN and version 1 and stores createdBy from X-User-Id`() {
        val freezeAt = Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.MILLIS)
        val res = create(body(title = "  Rain?  ", description = "Will it rain", freezeAt = freezeAt, minBet = 5, maxBet = 50)).andExpect {
            status { isCreated() }
            jsonPath("$.title") { value("Rain?") }
            jsonPath("$.description") { value("Will it rain") }
            jsonPath("$.status") { value("OPEN") }
            jsonPath("$.winningSide") { value(null) }
            jsonPath("$.freezeAt") { value(freezeAt.toString()) }
            jsonPath("$.minBet") { value(5) }
            jsonPath("$.maxBet") { value(50) }
            jsonPath("$.version") { value(1) }
        }.andReturn().response.contentAsString
        val id = UUID.fromString(EventJson.mapper.readTree(res).get("marketId").asString())
        assertEquals(admin, jdbc.sql("SELECT created_by FROM markets WHERE id = ?").param(id).query(UUID::class.java).single())
    }

    @Test
    fun `create market without description stores an empty description`() {
        val now = Instant.now()
        create("""{"title":"t","freezeAt":"${now.plusSeconds(60)}","endAt":"${now.plusSeconds(120)}","minBet":1,"maxBet":1}""")
            .andExpect { status { isCreated() }; jsonPath("$.description") { value("") } }
    }

    @Test
    fun `create market rejects blank title, past freezeAt, endAt not after freezeAt, minBet below 1, and maxBet below minBet with 400 VALIDATION_ERROR`() {
        val f = Instant.now().plusSeconds(3600)
        listOf(
            body(title = "   "),
            body(title = "x".repeat(201)),
            body(description = "x".repeat(2001)),
            body(freezeAt = Instant.now().minusSeconds(1)),
            body(freezeAt = f, endAt = f),
            body(minBet = 0),
            body(minBet = 10, maxBet = 9),
            """{"title":"t"}""",
        ).forEach { badRequest(create(it)) }
    }

    @Test
    fun `market endpoints return 403 FORBIDDEN without the ADMIN role and 400 without identity headers`() {
        val id = createId()
        mvc.post("/api/markets/$id/freeze") { header("X-User-Id", admin.toString()); header("X-User-Roles", "USER") }
            .andExpect { status { isForbidden() }; jsonPath("$.code") { value("FORBIDDEN") } }
        badRequest(mvc.post("/api/markets/$id/freeze"))
        badRequest(mvc.post("/api/markets/$id/freeze") { header("X-User-Id", admin.toString()) })
        badRequest(mvc.post("/api/markets/$id/freeze") { header("X-User-Id", "nope"); header("X-User-Roles", "ADMIN") })
        badRequest(mvc.post("/api/markets") { header("X-User-Roles", "ADMIN"); contentType = MediaType.APPLICATION_JSON; content = body() })
    }

    @Test
    fun `create market writes market created to the outbox and schedules freeze and end tasks at freezeAt and endAt`() {
        val freezeAt = Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.MILLIS)
        val endAt = freezeAt.plusSeconds(60)
        val id = createId(body(freezeAt = freezeAt, endAt = endAt))
        val (type, payload) = events(id).single()
        assertEquals(EventTypes.MARKET_CREATED, type)
        assertEquals(MarketV1(id, "Will it rain?", "d", "OPEN", null, freezeAt, endAt, 1, 100, 1), payload)
        assertEquals(freezeAt, taskTime("freeze-market", id))
        assertEquals(endAt, taskTime("end-market", id))
    }

    @Test
    fun `market created reaches Kafka keyed by market id with a MarketV1 payload`() {
        val id = createId()
        KafkaConsumer(
            mapOf<String, Any>(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers.joinToString(","),
                ConsumerConfig.GROUP_ID_CONFIG to UUID.randomUUID().toString(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
            ),
            StringDeserializer(), StringDeserializer(),
        ).use { consumer ->
            consumer.subscribe(listOf(EventTypes.MARKET_CREATED))
            val deadline = System.currentTimeMillis() + 30_000
            var found: ConsumerRecord<String, String>? = null
            while (found == null && System.currentTimeMillis() < deadline) {
                found = consumer.poll(Duration.ofMillis(500)).firstOrNull { it.key() == id.toString() }
            }
            assertNotNull(found)
            val market = EventJson.payload(EventJson.read(found.value()), MarketV1::class)
            assertEquals(id, market.marketId)
            assertEquals("OPEN", market.status)
        }
    }

    @Test
    fun `patch on an OPEN market updates fields, emits market updated, and reschedules both tasks to the new times`() {
        val id = createId()
        val freezeAt = Instant.now().plusSeconds(7200).truncatedTo(ChronoUnit.MILLIS)
        val endAt = freezeAt.plusSeconds(10)
        patch(id, """{"title":"New","freezeAt":"$freezeAt","endAt":"$endAt"}""").andExpect {
            status { isOk() }
            jsonPath("$.title") { value("New") }
            jsonPath("$.description") { value("d") }
            jsonPath("$.version") { value(2) }
        }
        val (type, payload) = events(id).last()
        assertEquals(EventTypes.MARKET_UPDATED, type)
        assertEquals(MarketV1(id, "New", "d", "OPEN", null, freezeAt, endAt, 1, 100, 2), payload)
        assertEquals(freezeAt, taskTime("freeze-market", id))
        assertEquals(endAt, taskTime("end-market", id))
    }

    @Test
    fun `patch schedules a task again when its instance no longer exists`() {
        val id = createId()
        jdbc.sql("DELETE FROM scheduled_tasks WHERE task_instance = ?").param(id.toString()).update()
        val freezeAt = Instant.now().plusSeconds(7200).truncatedTo(ChronoUnit.MILLIS)
        patch(id, """{"freezeAt":"$freezeAt","endAt":"${freezeAt.plusSeconds(5)}","description":"x"}""").andExpect { status { isOk() } }
        assertEquals(freezeAt, taskTime("freeze-market", id))
        assertEquals(freezeAt.plusSeconds(5), taskTime("end-market", id))
    }

    @Test
    fun `patch rejects an invalid merged market with 400 VALIDATION_ERROR`() {
        val id = createId()
        badRequest(patch(id, """{"title":" "}"""))
        badRequest(patch(id, """{"endAt":"${Instant.now().plusSeconds(10)}"}"""))
    }

    @Test
    fun `patch on a non-OPEN market returns 409 INVALID_MARKET_STATE and unknown market returns 404 MARKET_NOT_FOUND`() {
        val id = createId()
        freeze(id).andExpect { status { isOk() } }
        patch(id, """{"title":"x"}""").andExpect { status { isConflict() }; jsonPath("$.code") { value("INVALID_MARKET_STATE") } }
        patch(UUID.randomUUID(), """{"title":"x"}""").andExpect { status { isNotFound() }; jsonPath("$.code") { value("MARKET_NOT_FOUND") } }
    }

    @Test
    fun `manual freeze moves OPEN to FROZEN and emits market frozen, a second freeze returns 409`() {
        val id = createId()
        freeze(id).andExpect { status { isOk() }; jsonPath("$.status") { value("FROZEN") }; jsonPath("$.version") { value(2) } }
        assertEquals(EventTypes.MARKET_FROZEN to "FROZEN", events(id).last().let { it.first to it.second.status })
        freeze(id).andExpect { status { isConflict() }; jsonPath("$.code") { value("INVALID_MARKET_STATE") } }
    }

    @Test
    fun `resolve on an ENDED market sets winningSide and emits market resolved, resolve on a non-ENDED market returns 409`() {
        val id = createId()
        resolve(id, "TRUE").andExpect { status { isConflict() }; jsonPath("$.code") { value("INVALID_MARKET_STATE") } }
        setStatus(id, "ENDED")
        badRequest(resolve(id, "MAYBE"))
        resolve(id, "TRUE").andExpect { status { isOk() }; jsonPath("$.status") { value("RESOLVED") }; jsonPath("$.winningSide") { value("TRUE") } }
        val (type, payload) = events(id).last()
        assertEquals(EventTypes.MARKET_RESOLVED, type)
        assertEquals("TRUE", payload.winningSide)
    }

    @Test
    fun `delete cancels an OPEN, FROZEN, or ENDED market with 204 and emits market cancelled, delete on a RESOLVED market returns 409`() {
        listOf("OPEN", "FROZEN", "ENDED").forEach { s ->
            val id = createId()
            setStatus(id, s)
            cancel(id).andExpect { status { isNoContent() } }
            assertEquals("CANCELLED", status(id))
            assertEquals(EventTypes.MARKET_CANCELLED, events(id).last().first)
        }
        val id = createId()
        setStatus(id, "RESOLVED")
        cancel(id).andExpect { status { isConflict() }; jsonPath("$.code") { value("INVALID_MARKET_STATE") } }
        cancel(UUID.randomUUID()).andExpect { status { isNotFound() } }
    }
}
