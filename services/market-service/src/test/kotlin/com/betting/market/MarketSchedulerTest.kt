package com.betting.market

import com.betting.events.EventTypes
import com.github.kagkarlsson.scheduler.Scheduler
import com.github.kagkarlsson.scheduler.SchedulerName
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MarketSchedulerTest : IntegrationTest() {
    @Autowired lateinit var dataSource: DataSource
    @Autowired lateinit var tx: TransactionTemplate
    @Autowired lateinit var counter: ExecutionCounter
    @Autowired @Qualifier("freezeTask") lateinit var freezeTask: OneTimeTask<Void>
    @Autowired @Qualifier("endTask") lateinit var endTask: OneTimeTask<Void>

    private fun request(freezeAt: Instant = Instant.now().plusSeconds(3600)) =
        CreateMarketRequest("t", "d", freezeAt, freezeAt.plusSeconds(3600), 1, 10)

    private fun version(id: UUID) = jdbc.sql("SELECT version FROM markets WHERE id = ?").param(id).query(Long::class.java).single()

    @Test
    fun `freeze task fires at freezeAt and moves the market to FROZEN with one market frozen event`() {
        val id = service.create(request(Instant.now().plusSeconds(2)), admin).marketId
        await.atMost(timeout).untilAsserted { assertEquals("FROZEN", status(id)) }
        assertEquals(1, events(id).count { it.first == EventTypes.MARKET_FROZEN })
    }

    @Test
    fun `end task on a FROZEN market moves it to ENDED, on an OPEN market it emits market frozen then market ended`() {
        val frozen = service.create(request(), admin).marketId
        setStatus(frozen, "FROZEN")
        service.endByScheduler(frozen)
        assertEquals("ENDED", status(frozen))
        assertEquals(EventTypes.MARKET_ENDED to 2L, events(frozen).last().let { it.first to it.second.version })

        val open = service.create(request(), admin).marketId
        service.endByScheduler(open)
        assertEquals("ENDED", status(open))
        assertEquals(
            listOf(EventTypes.MARKET_CREATED to 1L, EventTypes.MARKET_FROZEN to 2L, EventTypes.MARKET_ENDED to 3L),
            events(open).map { it.first to it.second.version },
        )
    }

    @Test
    fun `scheduler handlers are no-ops on markets that already left the source state and on unknown market ids`() {
        val id = service.create(request(), admin).marketId
        setStatus(id, "FROZEN")
        service.freezeByScheduler(id)
        listOf("ENDED", "RESOLVED", "CANCELLED").forEach { s -> setStatus(id, s); service.endByScheduler(id) }
        service.freezeByScheduler(UUID.randomUUID())
        service.endByScheduler(UUID.randomUUID())
        assertEquals(1, events(id).size)
        assertEquals(1, version(id))
    }

    @Test
    fun `two scheduler instances on one database run each freeze task exactly once`() {
        val second = Scheduler.create(dataSource, freezeTask, endTask)
            .pollingInterval(Duration.ofMillis(200))
            .pollUsingLockAndFetch(0.5, 1.0)
            .schedulerName(SchedulerName.Fixed("second"))
            .threads(5)
            .addExecutionInterceptor(counter)
            .build()
        second.start()
        try {
            val freezeAt = Instant.now().plusSeconds(2)
            val ids = (1..20).map { service.create(request(freezeAt), admin).marketId }
            await.atMost(Duration.ofSeconds(30)).untilAsserted { ids.forEach { assertEquals("FROZEN", status(it)) } }
            // A finished one-time execution deletes its row; wait so no late duplicate run is missed.
            await.atMost(timeout).untilAsserted { ids.forEach { assertEquals(null, taskTime("freeze-market", it)) } }
            ids.forEach { id ->
                assertEquals(1, counter.counts["freeze-market/$id"]?.get(), "freeze executions of $id")
                assertEquals(1, events(id).count { it.first == EventTypes.MARKET_FROZEN })
            }
        } finally {
            second.stop()
        }
    }

    @Test
    fun `a dead freeze execution is revived and runs as a no-op after the first run already froze the market`() {
        val id = createId()
        freeze(id).andExpect { status { isOk() } }
        val updated = jdbc.sql(
            """UPDATE scheduled_tasks SET picked = true, picked_by = 'dead-node', last_heartbeat = now() - interval '1 day',
               execution_time = now() - interval '1 day' WHERE task_name = 'freeze-market' AND task_instance = ?""",
        ).param(id.toString()).update()
        assertEquals(1, updated)
        await.atMost(Duration.ofSeconds(30)).untilAsserted { assertEquals(null, taskTime("freeze-market", id)) }
        assertEquals(1, events(id).count { it.first == EventTypes.MARKET_FROZEN })
        assertEquals("FROZEN", status(id))
    }

    @Test
    fun `a transaction that fails after scheduling leaves no market, no outbox row, and no scheduled task`() {
        var id: UUID? = null
        assertFailsWith<IllegalStateException> {
            tx.execute { id = service.create(request(), admin).marketId; error("boom") }
        }
        val marketId = id!!
        assertEquals(0, jdbc.sql("SELECT count(*) FROM markets WHERE id = ?").param(marketId).query(Long::class.java).single())
        assertTrue(events(marketId).isEmpty())
        assertEquals(0, jdbc.sql("SELECT count(*) FROM scheduled_tasks WHERE task_instance = ?").param(marketId.toString()).query(Long::class.java).single())
    }
}
