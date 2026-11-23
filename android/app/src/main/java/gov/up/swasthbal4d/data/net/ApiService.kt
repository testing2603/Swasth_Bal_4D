package gov.up.swasthbal4d.data.net

import kotlinx.serialization.json.JsonObject
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming
import retrofit2.http.Url

interface ApiService {
    // auth
    @POST("auth/otp/request") suspend fun requestOtp(@Body body: OtpRequest): OtpRequestOut
    @POST("auth/otp/verify") suspend fun verifyOtp(@Body body: OtpVerify): TokenOut
    @POST("auth/pin/set") suspend fun setPin(@Body body: PinSet): JsonObject
    @POST("auth/pin/login") suspend fun pinLogin(@Body body: PinLogin): TokenOut

    // worker
    @GET("worker/home") suspend fun home(): HomeOut
    @GET("offline-pack") suspend fun offlinePack(): OfflinePack
    @POST("due/{id}/absent") suspend fun markAbsent(@Path("id") id: Int, @Body body: AbsentIn): AbsentOut
    @GET("children/qr/{code}") suspend fun childByQr(@Path("code") code: String): ChildCard
    @GET("children/{id}/card") suspend fun childCard(@Path("id") id: Int): ChildCard
    @GET("children/search") suspend fun search(@Query("q") q: String?, @Query("mobile") mobile: String?, @Query("wide") wide: Boolean = false): List<SearchHit>
    @POST("children") suspend fun register(@Body body: ChildIn): ChildCard

    // screening
    @POST("visits") suspend fun startVisit(@Body body: VisitCreate): JsonObject
    @POST("visits/{id}/consent/otp") suspend fun consentOtp(@Path("id") id: String): ConsentOtpOut
    @PUT("visits/{id}/consent") suspend fun consent(@Path("id") id: String, @Body body: ConsentIn): JsonObject
    @PUT("visits/{id}/danger-signs") suspend fun dangerSigns(@Path("id") id: String, @Body body: DangerIn): JsonObject
    @PUT("visits/{id}/domains/{d}") suspend fun saveDomain(@Path("id") id: String, @Path("d") d: String, @Body body: DomainIn): JsonObject
    @Multipart
    @POST("visits/{id}/media")
    suspend fun uploadMedia(
        @Path("id") id: String,
        @Part("domain") domain: RequestBody,
        @Part("kind") kind: RequestBody,
        @Part("tag") tag: RequestBody,
        @Part file: MultipartBody.Part,
    ): MediaOut
    @GET("visits/{id}/result") suspend fun result(@Path("id") id: String): WorkerResult
    @POST("sync/visits") suspend fun sync(@Body body: SyncIn): SyncOut

    // facility
    @GET("facility/inbox") suspend fun inbox(): InboxOut
    @GET("facility/referrals/{id}") suspend fun referral(@Path("id") id: Int): ReferralDetail
    @POST("facility/referrals/{id}/accept") suspend fun accept(@Path("id") id: Int, @Body body: AcceptIn): ReferralBrief
    @POST("facility/referrals/{id}/decline") suspend fun decline(@Path("id") id: Int, @Body body: DeclineIn): DeclineOut
    @POST("facility/checkin") suspend fun checkin(@Body body: CheckinIn): ReferralBrief
    @POST("facility/referrals/{id}/reached") suspend fun reached(@Path("id") id: Int): ReferralBrief
    @POST("facility/referrals/{id}/outcome") suspend fun outcome(@Path("id") id: Int, @Body body: OutcomeIn): JsonObject
    @Streaming @GET suspend fun download(@Url url: String): ResponseBody

    // department
    @GET("dashboard/summary") suspend fun dashboard(): DashboardOut
    @POST("dashboard/ask") suspend fun ask(@Body body: AskIn): AskOut
}
