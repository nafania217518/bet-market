package com.betting.query

import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.time.Duration

/** Cache-aside over Redis. Redis errors are logged and the loader (Mongo) answers. */
@Component
class Cache(private val redis: StringRedisTemplate, private val json: JsonMapper) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Suppress("UNCHECKED_CAST")
    fun getOrLoad(key: String, ttl: Duration, load: () -> Map<String, Any?>): Map<String, Any?> {
        try {
            redis.opsForValue().get(key)?.let { return json.readValue(it, Map::class.java) as Map<String, Any?> }
        } catch (e: Exception) {
            log.warn("Cache GET {} failed: {}", key, e.message)
        }
        val value = load()
        try {
            redis.opsForValue().set(key, json.writeValueAsString(value), ttl)
        } catch (e: Exception) {
            log.warn("Cache SET {} failed: {}", key, e.message)
        }
        return value
    }
}
