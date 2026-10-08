package com.betting.query

import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.data.redis.core.StringRedisTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import kotlin.test.assertEquals

class CacheTest {
    @Test
    fun `a failing Redis cache falls back to Mongo`() {
        val redis = mock(StringRedisTemplate::class.java)
        `when`(redis.opsForValue()).thenThrow(RuntimeException("redis down"))
        val cache = Cache(redis, JsonMapper.builder().build())
        assertEquals(mapOf("from" to "mongo"), cache.getOrLoad("k", Duration.ofSeconds(5)) { mapOf("from" to "mongo") })
    }
}
