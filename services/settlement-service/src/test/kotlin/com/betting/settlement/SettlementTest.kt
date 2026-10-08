package com.betting.settlement

import com.betting.events.BetV1
import com.betting.events.EventJson
import com.betting.events.EventTypes
import com.betting.events.PayoutCalculatedV1
import com.betting.events.PayoutV1
import com.betting.events.SettlementCompletedV1
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.mockito.Mockito.doCallRealMethod
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.KafkaTemplate
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class SettlementTest : IntegrationTest() {
    @Autowired lateinit var kafka: KafkaTemplate<String, String>
    @Autowired lateinit var service: SettlementService
    @Autowired lateinit var consumers: ConsumerFactory<String, String>

    private val a = UUID.randomUUID()
    private val b = UUID.randomUUID()

    private fun placed(vararg bs: BetV1) = bs.forEach { listener.onBetPlaced(envelope(EventTypes.BET_PLACED, it.marketId, it)) }
    private fun resolved(id: UUID, side: String = "TRUE") = listener.onMarketClosed(envelope(EventTypes.MARKET_RESOLVED, id, market(id, "RESOLVED", side)))

    private fun row(betId: UUID): Pair<Long, Boolean>? =
        jdbc.sql("SELECT amount, cancelled FROM settlement_bet WHERE bet_id = ?").param(betId)
            .query { rs, _ -> rs.getLong(1) to rs.getBoolean(2) }.optional().orElse(null)

    @Test
    fun `bet placed and bet cancelled build the settlement bet table in any order`() {
        val m = UUID.randomUUID()
        val first = bet(m, a, "TRUE", 10)
        val second = bet(m, b, "FALSE", 20)
        val kept = bet(m, b, "TRUE", 30)

        placed(first, first) // duplicate bet.placed is a no-op
        listener.onBetCancelled(envelope(EventTypes.BET_CANCELLED, m, first.copy(status = "CANCELLED")))
        listener.onBetCancelled(envelope(EventTypes.BET_CANCELLED, m, second.copy(status = "CANCELLED"))) // before bet.placed
        placed(second, kept)

        assertEquals(10L to true, row(first.betId))
        assertEquals(20L to true, row(second.betId))
        assertEquals(30L to false, row(kept.betId))
        assertEquals(3, bets.forMarket(m).size)
    }

    @Test
    fun `market resolved settles once - one payout calculated and one settlement completed in the outbox with the audit rows`() {
        val m = UUID.randomUUID()
        placed(bet(m, a, "TRUE", 100), bet(m, b, "FALSE", 300))
        resolved(m)
        await.atMost(timeout).untilAsserted { assertEquals("COMPLETED", status(m)) }

        assertEquals(listOf(PayoutCalculatedV1(m, listOf(PayoutV1(a, 380, "WIN")))), payoutEvents(m))
        assertEquals(listOf(SettlementCompletedV1(m, "RESOLVED", "TRUE", 100, 300, 380, 20, 2)), completedEvents(m))
        assertEquals(listOf(Triple(a, 380L, "WIN")), auditRows(m))
        assertEquals(listOf(EventTypes.PAYOUT_CALCULATED, EventTypes.SETTLEMENT_COMPLETED), outbox(m).map { it.first })
        val stored = jdbc.sql("SELECT true_pool, false_pool, paid_out, house_amount, bet_count, completed_at IS NOT NULL FROM settlements WHERE market_id = ?")
            .param(m).query { rs, _ -> listOf(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getInt(5).toLong(), rs.getBoolean(6)) }.single()
        assertEquals(listOf<Any>(100L, 300L, 380L, 20L, 2L, true), stored)
    }

    @Test
    fun `a duplicate market resolved does not settle twice`() {
        val m = UUID.randomUUID()
        placed(bet(m, a, "TRUE", 100), bet(m, b, "FALSE", 300))
        resolved(m)
        resolved(m)
        await.atMost(timeout).untilAsserted { assertEquals("COMPLETED", status(m)); assertEquals(0, taskCount(m)) }
        resolved(m) // after completion too
        assertEquals(0, taskCount(m))
        assertEquals(1, payoutEvents(m).size)
        assertEquals(1, completedEvents(m).size)
    }

    @Test
    fun `market cancelled refunds every stake through payout calculated`() {
        val m = UUID.randomUUID()
        placed(bet(m, a, "TRUE", 100), bet(m, a, "FALSE", 5), bet(m, b, "FALSE", 300))
        listener.onMarketClosed(envelope(EventTypes.MARKET_CANCELLED, m, market(m, "CANCELLED", null)))
        await.atMost(timeout).untilAsserted { assertEquals("COMPLETED", status(m)) }

        val refunds = listOf(PayoutV1(a, 105, "REFUND"), PayoutV1(b, 300, "REFUND")).sortedBy { it.userId }
        assertEquals(listOf(PayoutCalculatedV1(m, refunds)), payoutEvents(m))
        assertEquals(listOf(SettlementCompletedV1(m, "CANCELLED", null, 0, 0, 0, 0, 3)), completedEvents(m))
    }

    @Test
    fun `a market without bets still emits both events with an empty payout list`() {
        val m = UUID.randomUUID()
        resolved(m, "FALSE")
        await.atMost(timeout).untilAsserted { assertEquals("COMPLETED", status(m)) }
        assertEquals(listOf(PayoutCalculatedV1(m, emptyList())), payoutEvents(m))
        assertEquals(listOf(SettlementCompletedV1(m, "RESOLVED", "FALSE", 0, 0, 0, 0, 0)), completedEvents(m))
    }

    @Test
    fun `a settle task for an unknown market is a no-op`() {
        val m = UUID.randomUUID()
        service.settle(m)
        assertEquals(null, status(m))
        assertEquals(emptyList(), outbox(m))
    }

    @Test
    fun `a failing settlement run is retried by the scheduler and settles exactly once`() {
        val m = UUID.randomUUID()
        placed(bet(m, a, "TRUE", 100), bet(m, b, "FALSE", 300))
        doThrow(IllegalStateException("db hiccup")).doCallRealMethod().`when`(bets).forMarket(m)
        resolved(m)
        await.atMost(timeout).untilAsserted { assertEquals("COMPLETED", status(m)); assertEquals(0, taskCount(m)) }
        verify(bets, times(2)).forMarket(m)
        assertEquals(1, payoutEvents(m).size)
        assertEquals(1, completedEvents(m).size)
        assertEquals(1, auditRows(m).size)
    }

    @Test
    fun `market resolved from Kafka ends with payout calculated on Kafka keyed by market id`() {
        val m = UUID.randomUUID()
        listOf(bet(m, a, "TRUE", 100), bet(m, b, "FALSE", 300)).forEach {
            kafka.send(EventTypes.BET_PLACED, m.toString(), envelope(EventTypes.BET_PLACED, m, it)).get()
        }
        // Different topics have no shared order; the bets must be stored before the market resolves (architecture §8.6).
        await.atMost(Duration.ofSeconds(30)).untilAsserted { assertEquals(2, bets.forMarket(m).size) }
        kafka.send(EventTypes.MARKET_RESOLVED, m.toString(), envelope(EventTypes.MARKET_RESOLVED, m, market(m, "RESOLVED", "TRUE"))).get()

        consumers.createConsumer("test-${UUID.randomUUID()}", null).use { consumer ->
            consumer.subscribe(listOf(EventTypes.PAYOUT_CALCULATED))
            val mine = mutableListOf<PayoutCalculatedV1>()
            await.atMost(Duration.ofSeconds(30)).untilAsserted {
                consumer.poll(Duration.ofMillis(500)).filter { it.key() == m.toString() }
                    .forEach { mine += EventJson.payload(EventJson.read(it.value()), PayoutCalculatedV1::class) }
                assertEquals(listOf(PayoutCalculatedV1(m, listOf(PayoutV1(a, 380, "WIN")))), mine)
            }
        }
    }
}
