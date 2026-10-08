package com.betting.market

import org.springframework.http.MediaType
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals

class MarketLimitTest : IntegrationTest() {
    private val user: UUID = UUID.randomUUID()

    private fun createAsUser(): ResultActionsDsl = mvc.post("/api/markets") {
        header("X-User-Id", user.toString()); header("X-User-Roles", "USER"); contentType = MediaType.APPLICATION_JSON; content = body()
    }

    private fun userIds(): List<UUID> =
        jdbc.sql("SELECT id FROM markets WHERE created_by = ? ORDER BY created_at").param(user).query(UUID::class.java).list().filterNotNull()

    private fun createThree() = repeat(3) { createAsUser().andExpect { status { isCreated() } } }

    @Test
    fun `a user can create a market`() {
        createAsUser().andExpect { status { isCreated() } }
        assertEquals(1, userIds().size)
    }

    @Test
    fun `a user with 3 active markets gets 409 MARKET_LIMIT_REACHED on the fourth`() {
        createThree()
        val (a, b) = userIds()
        setStatus(a, "FROZEN"); setStatus(b, "ENDED")
        createAsUser().andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("MARKET_LIMIT_REACHED") }
            jsonPath("$.message") { value("You can have at most 3 active markets") }
        }
    }

    @Test
    fun `a resolved or cancelled market frees a slot`() {
        createThree()
        setStatus(userIds()[0], "RESOLVED")
        createAsUser().andExpect { status { isCreated() } }
        setStatus(userIds()[1], "CANCELLED")
        createAsUser().andExpect { status { isCreated() } }
        createAsUser().andExpect { status { isConflict() } }
    }

    @Test
    fun `an admin has no market limit`() {
        repeat(4) { create().andExpect { status { isCreated() } } }
    }

    @Test
    fun `parallel creates by one user never exceed 3 active markets`() {
        val pool = Executors.newFixedThreadPool(6)
        val codes = pool.invokeAll((1..6).map { Callable { createAsUser().andReturn().response.status } }).map { it.get() }
        pool.shutdown()
        assertEquals(3, codes.count { it == 201 })
        assertEquals(3, codes.count { it == 409 })
        assertEquals(3, userIds().size)
    }

    @Test
    fun `freeze, resolve, update and cancel still need ADMIN`() {
        val id = createId()
        fun forbidden(r: ResultActionsDsl) = r.andExpect { status { isForbidden() }; jsonPath("$.code") { value("FORBIDDEN") } }
        val u: org.springframework.test.web.servlet.MockHttpServletRequestDsl.() -> Unit = {
            header("X-User-Id", user.toString()); header("X-User-Roles", "USER"); contentType = MediaType.APPLICATION_JSON
        }
        forbidden(mvc.post("/api/markets/$id/freeze") { u() })
        forbidden(mvc.post("/api/markets/$id/resolve") { u(); content = """{"winningSide":"TRUE"}""" })
        forbidden(mvc.patch("/api/markets/$id") { u(); content = """{"title":"x"}""" })
        forbidden(mvc.delete("/api/markets/$id") { u() })
    }
}
