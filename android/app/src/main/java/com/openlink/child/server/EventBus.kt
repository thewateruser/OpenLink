package com.openlink.child.server

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.JsonElement

/**
 * Fan-out of the `/events` WebSocket messages in docs/PROTOCOL.md.
 *
 * Process-wide, because the producers are spread across the app -- the usage poller in the
 * foreground service emits `usage:update`, the REST routes emit `policy:update`, the child's own
 * "ask for more time" dialog emits `request:new` -- while the consumers are one coroutine per
 * connected parent inside the Ktor module.
 *
 * The buffer drops the oldest event when a slow or wedged parent connection falls behind. That
 * is the right trade for this protocol: every event type is a notification about state that can
 * be re-read over REST, and PROTOCOL.md explicitly makes the socket a convenience rather than a
 * requirement. Blocking an enforcement-path coroutine on a stalled socket would not be.
 */
object EventBus {

    /**
     * @param exceptParentId the parent whose own action produced this event, if any. PROTOCOL.md
     *        scopes `policy:update` to changes made by *another* parent, so the originator is
     *        skipped -- it already has the result in its HTTP response.
     */
    data class Outbound(val envelope: EventEnvelope, val exceptParentId: String? = null)

    const val TYPE_REQUEST_NEW = "request:new"
    const val TYPE_USAGE_UPDATE = "usage:update"
    const val TYPE_POLICY_UPDATE = "policy:update"
    const val TYPE_DEVICE_STATE = "device:state"

    private val _events = MutableSharedFlow<Outbound>(
        replay = 0,
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val events: SharedFlow<Outbound> = _events.asSharedFlow()

    private fun emit(type: String, payload: JsonElement, exceptParentId: String? = null) {
        _events.tryEmit(Outbound(EventEnvelope(type, payload), exceptParentId))
    }

    fun requestNew(request: TimeRequestDto) =
        emit(TYPE_REQUEST_NEW, apiJson.encodeToJsonElement(TimeRequestDto.serializer(), request))

    fun usageUpdate(packageName: String, minutesUsed: Int, date: String) =
        emit(
            TYPE_USAGE_UPDATE,
            apiJson.encodeToJsonElement(
                UsageUpdatePayload.serializer(),
                UsageUpdatePayload(packageName, minutesUsed, date)
            )
        )

    fun policyUpdate(
        policies: List<PolicyDto>,
        schedule: List<ScheduleWindowDto>,
        exceptParentId: String? = null
    ) = emit(
        TYPE_POLICY_UPDATE,
        apiJson.encodeToJsonElement(
            PolicyUpdatePayload.serializer(),
            PolicyUpdatePayload(policies, schedule)
        ),
        exceptParentId
    )

    fun deviceState(batteryLevel: Int?, endpoints: List<String>) =
        emit(
            TYPE_DEVICE_STATE,
            apiJson.encodeToJsonElement(
                DeviceStatePayload.serializer(),
                DeviceStatePayload(batteryLevel, endpoints)
            )
        )
}
