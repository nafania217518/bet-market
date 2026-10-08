package com.betting.user

import com.betting.events.EventJson
import com.betting.events.EventTypes
import com.betting.events.UserRegisteredV1
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RegisterTest : IntegrationTest() {
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var kafka: KafkaConnectionDetails

    private fun registerId(email: String, displayName: String = "Al"): UUID {
        val body = register(email, displayName = displayName).andExpect { status { isCreated() } }.andReturn().response.contentAsString
        return UUID.fromString(JsonMapper().readTree(body).get("userId").asString())
    }

    @Test
    fun `register creates a user and returns 201 with the user id`() {
        val id = registerId(uniqueEmail())
        assertTrue(users.existsById(id))
    }

    @Test
    fun `register stores the email lowercased and the password as a BCrypt hash`() {
        val email = uniqueEmail()
        val user = users.findById(registerId("  $email ")).get()
        assertEquals(email.lowercase(), user.email)
        assertTrue(user.passwordHash.startsWith("\$2a\$12\$"))
        assertTrue(BCryptPasswordEncoder().matches("password123", user.passwordHash))
    }

    @Test
    fun `register rejects a duplicate email with 409 EMAIL_TAKEN`() {
        val email = uniqueEmail()
        registerId(email)
        register(email.uppercase()).andExpect { status { isConflict() }; jsonPath("$.code") { value("EMAIL_TAKEN") } }
    }

    @Test
    fun `concurrent registrations with the same email return one 201 and 409 EMAIL_TAKEN for the rest`() {
        val email = uniqueEmail()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        val statuses = try {
            (1..4).map { pool.submit<Int> { register(email).andReturn().response.status } }.map { it.get() }
        } finally {
            pool.shutdown()
        }
        assertEquals(listOf(201, 409, 409, 409), statuses.sorted())
    }

    @Test
    fun `register rejects an invalid email, short password, or blank display name with 400 VALIDATION_ERROR`() {
        listOf(
            register("no-at-sign"),
            register(uniqueEmail(), password = "short"),
            register(uniqueEmail(), displayName = "   "),
            register(uniqueEmail(), displayName = "x".repeat(51)),
            postJson("/api/auth/register", """{"email":"a@b.c"}"""),
        ).forEach { it.andExpect { status { isBadRequest() }; jsonPath("$.code") { value("VALIDATION_ERROR") } } }
    }

    @Test
    fun `register writes a user registered event to the outbox in the same transaction`() {
        val id = registerId(uniqueEmail(), displayName = " Bob ")
        val payload = jdbc.sql("SELECT payload::text FROM outbox_event WHERE aggregate_id = ? AND event_type = ?")
            .params(id.toString(), EventTypes.USER_REGISTERED).query(String::class.java).single()
        val event = EventJson.payload(EventJson.read(payload), UserRegisteredV1::class)
        assertEquals(id, event.userId)
        assertEquals("Bob", event.displayName)
        assertEquals(users.findById(id).get().email, event.email)
    }

    @Test
    fun `user registered reaches Kafka with the user id as key and a UserRegisteredV1 payload`() {
        val email = uniqueEmail()
        val id = registerId(email)
        KafkaConsumer(
            mapOf<String, Any>(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers.joinToString(","),
                ConsumerConfig.GROUP_ID_CONFIG to UUID.randomUUID().toString(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
            ),
            StringDeserializer(), StringDeserializer(),
        ).use { consumer ->
            consumer.subscribe(listOf(EventTypes.USER_REGISTERED))
            val deadline = System.currentTimeMillis() + 30_000
            var found: ConsumerRecord<String, String>? = null
            while (found == null && System.currentTimeMillis() < deadline) {
                found = consumer.poll(Duration.ofMillis(500)).firstOrNull { it.key() == id.toString() }
            }
            assertNotNull(found)
            assertEquals(UserRegisteredV1(id, email.lowercase(), "Al"), EventJson.payload(EventJson.read(found.value()), UserRegisteredV1::class))
        }
    }
}
