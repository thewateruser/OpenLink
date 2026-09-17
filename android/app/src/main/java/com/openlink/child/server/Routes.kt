package com.openlink.child.server

import com.openlink.child.domain.ApiError
import com.openlink.child.domain.ChildRepository
import com.openlink.child.pairing.PairingSession
import com.openlink.child.pairing.ParentRegistry
import com.openlink.child.prefs.PairedParent
import com.openlink.child.security.Crypto
import com.openlink.child.security.RateLimiter
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
// Needed by the intercept() gate below: inside a route handler `call` is a
// member of Ktor 3's RoutingContext, but inside a pipeline interceptor it is
// an extension on PipelineContext and so has to be imported.
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.contentLength
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.request.receiveNullable
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/** Everything the Ktor module needs, assembled by OpenLinkServer. */
class ServerDependencies(
    val repository: ChildRepository,
    val registry: ParentRegistry,
    /** The port actually bound, which may not be the default if 8765 was busy. */
    val port: Int
)

/** Bodies larger than this are refused before any parsing happens. */
private const val MAX_BODY_BYTES = 256L * 1024L

/**
 * The listener's routes, exactly as specified in docs/PROTOCOL.md.
 *
 * The security posture is "deny by default at the pipeline, not per route": a single interceptor
 * ahead of routing requires a valid bearer token for *everything* and carves out one exception,
 * `POST /pair`. A route added later is therefore authenticated by construction -- forgetting to
 * wrap it can't happen, because there is nothing to wrap.
 */
fun Application.openLinkModule(deps: ServerDependencies) {

    val authGuard = AuthGuard(deps.registry)

    // docs/PROTOCOL.md: 5 attempts per minute on the pairing route. The global ceiling is
    // deliberately low too -- a pairing window is five minutes of one human scanning one QR, so
    // there is no legitimate traffic pattern this gets in the way of.
    val pairLimiter = RateLimiter(
        maxPerKey = 5,
        maxGlobal = 20,
        windowMillis = 60_000L
    )

    install(ContentNegotiation) { json(apiJson) }

    install(WebSockets) {
        // A parent never sends us anything large; this is a cheap bound on a stranger's ability
        // to make the device allocate.
        maxFrameSize = 64L * 1024L
    }

    install(StatusPages) {
        exception<ApiError> { call, cause ->
            call.respond(HttpStatusCode.fromValue(cause.statusCode), ErrorResponse(cause.reason))
        }
        exception<BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("malformed request"))
        }
        exception<Throwable> { call, _ ->
            // Never echo the exception: a stack trace or message from this process is free
            // reconnaissance for anyone who can reach the socket.
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("internal error"))
        }
    }

    // ---- the gate ---------------------------------------------------------------------------
    intercept(ApplicationCallPipeline.ApplicationPhase.Plugins) {
        if ((call.request.contentLength() ?: 0L) > MAX_BODY_BYTES) {
            call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("body too large"))
            return@intercept finish()
        }

        val isPairingAttempt =
            call.request.path() == "/pair" && call.request.httpMethod == HttpMethod.Post
        if (isPairingAttempt) return@intercept

        when (val result = authGuard.authenticate(call)) {
            is AuthGuard.Result.Authenticated ->
                call.attributes.put(ParentAttributeKey, result.parent)

            AuthGuard.Result.RateLimited -> {
                call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("too many attempts"))
                return@intercept finish()
            }

            AuthGuard.Result.Unauthorized -> {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized"))
                return@intercept finish()
            }
        }
    }

    routing {

        // ---- pairing: the only unauthenticated route ------------------------------------------
        post("/pair") {
            if (!pairLimiter.tryAcquire(AuthGuard.remoteKey(call))) {
                call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("too many attempts"))
                return@post
            }

            // Outside an open pairing window there is no secret to verify against, so the route
            // is inert -- which is the point: it only answers while a human is looking at the
            // QR on the child's screen.
            if (!PairingSession.isActive()) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("pairing is not open"))
                return@post
            }

            val body = call.receive<PairRequest>()

            // A parentId is 32 random bytes, base64url. Checking the shape before the HMAC keeps
            // a malformed id from being silently accepted as a legitimate identity.
            val parentIdBytes = Crypto.base64UrlDecode(body.parentId)
            if (parentIdBytes == null || parentIdBytes.size != PARENT_ID_BYTES) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid parentId"))
                return@post
            }

            if (!PairingSession.verifyAndConsume(body.parentId, body.proof)) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("pairing proof rejected"))
                return@post
            }

            val token = deps.registry.register(body.parentId, body.parentName ?: "Parent")
            call.respond(
                PairResponse(
                    deviceId = deps.repository.deviceId(),
                    deviceName = deps.repository.deviceName(),
                    parentToken = token,
                    endpoints = DeviceInfo.endpoints(deps.port)
                )
            )
        }

        // ---- device -----------------------------------------------------------------------------
        get("/device") {
            call.respond(deps.repository.deviceSnapshot(deps.port))
        }

        delete("/pair") {
            val caller = call.requireParent()
            // The body is optional: no body (or an empty one) means "unpair me".
            val body = try {
                call.receiveNullable<UnpairRequest>()
            } catch (e: Exception) {
                null
            }
            val targetParentId = body?.parentId?.takeIf { it.isNotBlank() } ?: caller.parentId
            deps.registry.revoke(targetParentId)
            call.respond(HttpStatusCode.NoContent)
        }

        // ---- apps and policies -------------------------------------------------------------------
        get("/apps") {
            call.respond(deps.repository.apps())
        }

        put("/policies/{packageName}") {
            val parent = call.requireParent()
            val packageName = call.parameters["packageName"]
                ?: throw ApiError(400, "missing package name")

            // Decoded as a raw object rather than a data class on purpose: PROTOCOL.md gives
            // `dailyLimitMinutes` three meanings -- absent (leave alone), null (clear the limit)
            // and a number (set it) -- and a nullable Kotlin field can only express two of them.
            val body = call.receive<JsonObject>()
            val limitElement = body["dailyLimitMinutes"]
            val limitProvided = body.containsKey("dailyLimitMinutes")
            val dailyLimitMinutes = when {
                !limitProvided -> null
                limitElement is JsonNull -> null
                else -> (limitElement as? JsonPrimitive)?.intOrNull
                    ?: throw ApiError(400, "invalid dailyLimitMinutes")
            }
            val blocked = body["blocked"]?.let { element ->
                (element as? JsonPrimitive)?.booleanOrNull
                    ?: throw ApiError(400, "invalid blocked")
            }

            val updated = deps.repository.updatePolicy(
                packageName = packageName,
                patch = PolicyPatch(limitProvided, dailyLimitMinutes, blocked),
                originParentId = parent.parentId
            )
            call.respond(updated)
        }

        get("/schedule") {
            call.respond(ScheduleDto(deps.repository.schedule()))
        }

        put("/schedule") {
            val parent = call.requireParent()
            val body = call.receive<ScheduleDto>()
            val windows = deps.repository.replaceSchedule(body.windows, parent.parentId)
            call.respond(ScheduleDto(windows))
        }

        // ---- usage ----------------------------------------------------------------------------------
        get("/usage") {
            call.respond(deps.repository.usage(call.request.queryParameters["date"]))
        }

        // ---- time requests ---------------------------------------------------------------------------
        get("/requests") {
            call.respond(deps.repository.requests(call.request.queryParameters["status"]))
        }

        post("/requests/{id}/approve") {
            val id = call.parameters["id"] ?: throw ApiError(400, "missing id")
            val body = call.receive<ApproveRequest>()
            call.respond(deps.repository.approveRequest(id, body.grantedMinutes))
        }

        post("/requests/{id}/deny") {
            val id = call.parameters["id"] ?: throw ApiError(400, "missing id")
            val body = try {
                call.receiveNullable<DenyRequest>()
            } catch (e: Exception) {
                null
            }
            call.respond(deps.repository.denyRequest(id, body?.reason))
        }

        // ---- live events -------------------------------------------------------------------------------
        webSocket("/events") {
            // The gate above already ran for the handshake request, so reaching here means a
            // valid token was presented. The null branch is belt-and-braces.
            val parent = call.attributes.getOrNull(ParentAttributeKey)
            if (parent == null) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))
                return@webSocket
            }

            ServerState.onParentConnected()

            // A bearer token is checked once, at the handshake. Without the epoch check below, a
            // parent revoked mid-session would keep receiving live events for as long as it held
            // this socket open -- which is precisely the situation someone hits the revoke button
            // to end.
            var knownEpoch = ParentRegistry.currentRevocationEpoch()

            val sender = launch {
                EventBus.events.collect { outbound ->
                    val epoch = ParentRegistry.currentRevocationEpoch()
                    if (epoch != knownEpoch) {
                        knownEpoch = epoch
                        if (!deps.registry.isStillPaired(parent.parentId)) {
                            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "revoked"))
                            return@collect
                        }
                    }
                    // `policy:update` is scoped to changes made by *another* parent.
                    if (outbound.exceptParentId == parent.parentId) return@collect
                    send(
                        Frame.Text(
                            apiJson.encodeToString(EventEnvelope.serializer(), outbound.envelope)
                        )
                    )
                }
            }

            try {
                // Parents have nothing to say over this socket; draining `incoming` is how the
                // close handshake and connection loss are noticed.
                for (frame in incoming) {
                    // Ignored by design.
                }
            } catch (e: Throwable) {
                // Connection dropped; the finally block cleans up.
            } finally {
                sender.cancel()
                ServerState.onParentDisconnected()
            }
        }
    }
}

private const val PARENT_ID_BYTES = 32

/**
 * The parent behind the current call. The pipeline gate guarantees this is set for every route
 * except `POST /pair`.
 */
private fun ApplicationCall.requireParent(): PairedParent =
    attributes.getOrNull(ParentAttributeKey) ?: throw ApiError(401, "unauthorized")
