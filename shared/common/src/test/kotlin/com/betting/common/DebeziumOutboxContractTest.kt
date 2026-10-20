package com.betting.common

import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.MountableFile
import tools.jackson.databind.json.JsonMapper
import java.io.File
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Contract for the compose/k8s relay: real Postgres init SQL, real connector config, real register.sh.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DebeziumOutboxContractTest {
    private val repo = File("../..").canonicalFile
    private val network = Network.newNetwork()
    private val postgres = PostgreSQLContainer("postgres:16-alpine")
        .withNetwork(network).withNetworkAliases("postgres")
        .withUsername("postgres").withPassword("postgres")
        .withCommand("postgres", "-c", "wal_level=logical")
        .withCopyFileToContainer(MountableFile.forHostPath("$repo/infra/postgres/init/01-databases.sql"), "/docker-entrypoint-initdb.d/01-databases.sql")
        .withCopyFileToContainer(MountableFile.forHostPath("$repo/infra/postgres/init/02-debezium.sql"), "/docker-entrypoint-initdb.d/02-debezium.sql")
    private val kafka = KafkaContainer("apache/kafka:4.3.1")
        .withNetwork(network).withNetworkAliases("kafka")
        .withListener("kafka:19092")
    private val connect = GenericContainer("quay.io/debezium/connect:3.7.0.Final")
        .withNetwork(network)
        .withEnv(mapOf(
            "BOOTSTRAP_SERVERS" to "kafka:19092", "GROUP_ID" to "outbox",
            "CONFIG_STORAGE_TOPIC" to "connect_configs", "OFFSET_STORAGE_TOPIC" to "connect_offsets", "STATUS_STORAGE_TOPIC" to "connect_statuses",
        ))
        .withExposedPorts(8083)
        .withCopyFileToContainer(MountableFile.forHostPath("$repo/infra/debezium"), "/debezium")
        .waitingFor(Wait.forHttp("/connectors").forPort(8083).withStartupTimeout(Duration.ofMinutes(3)))

    private lateinit var userDb: JdbcClient
    private lateinit var consumer: KafkaConsumer<String, String>
    private val json = JsonMapper.builder().build()
    private val topic = "contract.event"
    private val early = UUID.randomUUID()

    private fun jdbc(db: String, user: String = "app") =
        JdbcClient.create(DriverManagerDataSource(postgres.jdbcUrl.replace("/test", "/$db"), user, user))

    private fun insert(aggregateId: String, correlationId: String? = "corr-$aggregateId", id: UUID = UUID.randomUUID()): UUID {
        val envelope = """{"eventId":"$id","eventType":"$topic","aggregateId":"$aggregateId","payload":{"n":1}}"""
        userDb.sql("INSERT INTO outbox_event(id, aggregate_id, event_type, payload, correlation_id) VALUES (?, ?, ?, ?::jsonb, ?)")
            .params(listOf(id, aggregateId, topic, envelope, correlationId)).update()
        return id
    }

    // Every record seen so far, so tests do not depend on run order.
    private val received = mutableListOf<ConsumerRecord<String, String>>()

    private fun awaitKey(key: String): ConsumerRecord<String, String> {
        val deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos()
        while (System.nanoTime() < deadline) {
            received.firstOrNull { it.key() == key }?.let { return it }
            consumer.poll(Duration.ofMillis(500)).forEach(received::add)
        }
        error("no message with key $key on $topic within 60 s")
    }

    @BeforeAll
    fun start() {
        postgres.start(); kafka.start()
        Flyway.configure().dataSource(postgres.jdbcUrl.replace("/test", "/user_db"), "app", "app").load().migrate()
        userDb = jdbc("user_db")
        AdminClient.create(mapOf("bootstrap.servers" to kafka.bootstrapServers)).use {
            it.createTopics(listOf(NewTopic(topic, 1, 1))).all().get()
        }
        insert("early", id = early) // before Connect exists
        connect.start()
        val result = connect.execInContainer("env", "CONNECT_URL=http://localhost:8083", "DBS=user_db", "sh", "/debezium/register.sh")
        assertEquals(0, result.exitCode, result.stdout + result.stderr)
        consumer = KafkaConsumer(mapOf(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers,
            ConsumerConfig.GROUP_ID_CONFIG to "contract-${UUID.randomUUID()}",
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
        ), StringDeserializer(), StringDeserializer())
        consumer.subscribe(listOf(topic))
    }

    @AfterAll
    fun stop() {
        consumer.close(); connect.stop(); kafka.stop(); postgres.stop(); network.close()
    }

    @Test
    fun `an outbox row reaches the topic named by event_type, keyed by aggregate_id, with the envelope as value and the correlation header`() {
        val id = insert("agg-1")
        val record = awaitKey("agg-1")
        val stored = userDb.sql("SELECT payload::text FROM outbox_event WHERE id = ?").param(id).query(String::class.java).single()
        assertEquals(json.readTree(stored), json.readTree(record.value()))
        assertEquals("corr-agg-1", String(record.headers().lastHeader(CorrelationId.HEADER).value()))
    }

    @Test
    fun `a row inserted before Connect starts is still delivered`() {
        val record = awaitKey("early")
        assertEquals(early.toString(), json.readTree(record.value()).get("eventId").asString())
    }

    @Test
    fun `a row without correlation id is delivered without failing the task`() {
        insert("no-corr", correlationId = null)
        assertEquals("no-corr", awaitKey("no-corr").key())
    }

    @Test
    fun `a purge DELETE emits no message`() {
        insert("before-purge")
        awaitKey("before-purge")
        val mark = received.size
        userDb.sql("DELETE FROM outbox_event").update()
        insert("marker")
        awaitKey("marker")
        assertEquals(listOf("marker"), received.drop(mark).map { it.key() })
        assertTrue(received.drop(mark).all { it.value() != null })
    }

    @Test
    fun `the slot of a quiet database keeps up through the heartbeat`() {
        val admin = jdbc("postgres", "postgres")
        val wallet = jdbc("wallet_db")
        wallet.sql("CREATE TABLE IF NOT EXISTS scratch(n int)").update()
        wallet.sql("INSERT INTO scratch SELECT generate_series(1, 10000)").update()
        val lsn = admin.sql("SELECT pg_current_wal_lsn()::text").query(String::class.java).single()
        await atMost Duration.ofSeconds(60) untilAsserted {
            val behind = admin.sql("SELECT confirmed_flush_lsn < ?::pg_lsn FROM pg_replication_slots WHERE slot_name = 'outbox_user_db'")
                .param(lsn).query(Boolean::class.java).single()
            assertEquals(false, behind)
        }
    }
}
