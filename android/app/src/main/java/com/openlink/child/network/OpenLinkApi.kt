package com.openlink.child.network

import com.openlink.child.network.model.DevicePoliciesResponse
import com.openlink.child.network.model.PairingClaimRequest
import com.openlink.child.network.model.PairingClaimResponse
import com.openlink.child.network.model.SyncAppsRequest
import com.openlink.child.network.model.TimeRequestCreate
import com.openlink.child.network.model.TimeRequestDto
import com.openlink.child.network.model.UsageHeartbeatRequest
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

/**
 * REST surface this app calls, matching docs/API.md's "Pairing" and "Child device self-service"
 * sections. Base URL is resolved at request time by [DynamicBaseUrlInterceptor] (see
 * [NetworkModule]) since the server host is only known once the user enters it in onboarding.
 */
interface OpenLinkApi {

    /** No auth required -- the pairing code itself is the credential. */
    @POST("pairing/claim")
    suspend fun claimPairing(@Body body: PairingClaimRequest): PairingClaimResponse

    @POST("device/apps")
    suspend fun syncApps(@Body body: SyncAppsRequest)

    /** Also updates the device's `lastSeenAt` on the server, per docs/API.md. */
    @POST("device/usage")
    suspend fun postUsage(@Body body: UsageHeartbeatRequest)

    @GET("device/policies")
    suspend fun getPolicies(): DevicePoliciesResponse

    @POST("device/requests")
    suspend fun createTimeRequest(@Body body: TimeRequestCreate): TimeRequestDto

    @GET("device/requests")
    suspend fun getRequests(): List<TimeRequestDto>
}
