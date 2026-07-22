package org.svt.mdm.transport

import okhttp3.RequestBody
import org.svt.mdm.transport.dto.CheckinRequest
import org.svt.mdm.transport.dto.CommandAck
import org.svt.mdm.transport.dto.EnrollRequest
import org.svt.mdm.transport.dto.EnrollResponse
import org.svt.mdm.transport.dto.InventoryRequest
import org.svt.mdm.transport.dto.LocationRequest
import org.svt.mdm.transport.dto.ManifestRequest
import org.svt.mdm.transport.dto.ManifestResponse
import org.svt.mdm.transport.dto.OkResponse
import org.svt.mdm.transport.dto.PendingCommands
import org.svt.mdm.transport.dto.RunCompleteRequest
import org.svt.mdm.transport.dto.RunStartResponse
import org.svt.mdm.transport.dto.UploadResponse
import org.svt.mdm.transport.dto.UsageRequest
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

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

    // --- Backups ---
    @POST("api/backup/run")
    suspend fun backupStart(): RunStartResponse

    @POST("api/backup/manifest")
    suspend fun backupManifest(@Body body: ManifestRequest): ManifestResponse

    @PUT("api/backup/object/{sha256}")
    suspend fun backupUpload(
        @Path("sha256") sha256: String,
        @Query("path") path: String,
        @Query("category") category: String,
        @Body body: RequestBody,
    ): UploadResponse

    @POST("api/backup/run/{runId}/complete")
    suspend fun backupComplete(
        @Path("runId") runId: String,
        @Body body: RunCompleteRequest,
    ): OkResponse
}
