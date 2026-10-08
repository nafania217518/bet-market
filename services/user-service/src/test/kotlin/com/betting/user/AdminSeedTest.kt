package com.betting.user

import com.nimbusds.jwt.SignedJWT
import tools.jackson.databind.json.JsonMapper
import kotlin.test.Test
import kotlin.test.assertEquals

class AdminSeedTest : IntegrationTest() {
    private fun adminLogin() = login("admin@bet-market.local", "admin-password")

    @Test
    fun `the seeded admin can log in with the configured credentials`() {
        adminLogin().andExpect { status { isOk() } }
    }

    @Test
    fun `the admin access token has the roles USER and ADMIN`() {
        val body = adminLogin().andReturn().response.contentAsString
        val token = SignedJWT.parse(JsonMapper().readTree(body).get("accessToken").asString())
        assertEquals(listOf("USER", "ADMIN"), token.jwtClaimsSet.getStringListClaim("roles"))
    }
}
