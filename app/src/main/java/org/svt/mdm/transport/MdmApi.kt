package org.svt.mdm.transport

import org.svt.mdm.transport.dto.CheckinRequest
import org.svt.mdm.transport.dto.CommandAck
import org.svt.mdm.transport.dto.EnrollRequest
import org.svt.mdm.transport.dto.EnrollResponse
import org.svt.mdm.transport.dto.InventoryRequest
import org.svt.mdm.transport.dto.LocationRequest
import org.svt.mdm.transport.dto.OkResponse
import org.svt.mdm.transport.dto.PendingCommands
import org.svt.mdm.transport.dto.UsageRequest
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

/**
 * Retrofit view of the server's JSON API. The bearer token is injected by an
 * OkHttp interceptor (see [ApiClientFactory]), so it does not appear here.
 */
interface MdmApi {
    @POST("api/enroll")
    suspend fun enroll(@Body body: EnrollRequest): EnrollResponse

    @POST("api/telemetry/checkin")
    suspend fun checkin(@Body body: CheckinRequest): OkResponse

    @POST("api/telemetry/location")
    suspend fun location(@Body body: LocationRequest): OkResponse

    @POST("api/telemetry/inventory")
    suspend fun inventory(@Body body: InventoryRequest): OkResponse

    @POST("api/telemetry/usage")
    suspend fun usage(@Body body: UsageRequest): OkResponse

    @GET("api/commands/pending")
    suspend fun pendingCommands(): PendingCommands

    @POST("api/commands/ack")
    suspend fun ackCommand(@Body body: CommandAck): OkResponse
}
