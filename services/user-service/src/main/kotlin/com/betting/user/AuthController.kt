package com.betting.user

import com.betting.common.ApiException
import com.betting.common.OutboxWriter
import com.betting.events.EventTypes
import com.betting.events.UserRegisteredV1
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class RegisterRequest(val email: String, val password: String, val displayName: String)
data class RegisterResponse(val userId: UUID)
data class LoginRequest(val email: String, val password: String)
data class LoginResponse(val accessToken: String, val tokenType: String = "Bearer", val expiresIn: Long = JwtIssuer.TTL.seconds)

// No validation starter on the classpath: validate by hand.
@RestController
class AuthController(
    private val users: UserRepository,
    private val outbox: OutboxWriter,
    private val jwt: JwtIssuer,
) {
    private val encoder = BCryptPasswordEncoder(12)

    @PostMapping("/api/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    fun register(@RequestBody req: RegisterRequest): RegisterResponse {
        val email = req.email.trim().lowercase()
        val displayName = req.displayName.trim()
        if (!email.contains('@')) invalid("email must contain @")
        if (req.password.length < 8) invalid("password must have at least 8 characters")
        if (displayName.length !in 1..50) invalid("displayName must have 1..50 characters")
        // The UNIQUE constraint is the only duplicate check: a check-then-insert races.
        val user = try {
            users.saveAndFlush(User(UUID.randomUUID(), email, encoder.encode(req.password)!!, displayName))
        } catch (e: DataIntegrityViolationException) {
            throw ApiException(HttpStatus.CONFLICT, "EMAIL_TAKEN", "Email is already registered")
        }
        outbox.append(EventTypes.USER_REGISTERED, user.id.toString(), UserRegisteredV1(user.id, email, displayName))
        return RegisterResponse(user.id)
    }

    @PostMapping("/api/auth/login")
    fun login(@RequestBody req: LoginRequest): LoginResponse {
        val user = users.findByEmail(req.email.trim().lowercase())
        if (user == null || !encoder.matches(req.password, user.passwordHash)) {
            throw ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid email or password")
        }
        return LoginResponse(jwt.issue(user.id, user.roleList()))
    }

    @GetMapping("/.well-known/jwks.json")
    fun jwks(): Map<String, Any> = jwt.publicJwks()

    private fun invalid(message: String): Nothing = throw ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message)
}
