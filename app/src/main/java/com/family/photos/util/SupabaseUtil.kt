package com.family.photos.util

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

object SupabaseUtil {

    private const val SUPABASE_URL = "https://plobrqfaqtcihzzakmbk.supabase.co"
    private const val SUPABASE_KEY = "sb_publishable_xYICMLO85xY97yxuWhAGbA_bddfdQ9P"
    private const val PREFS_NAME = "family_photos_prefs"
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_REFRESH_TOKEN = "refresh_token"
    const val KEY_SAVED_EMAIL = "saved_email"
    const val KEY_SAVED_PASSWORD = "saved_password"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private var accessToken: String = ""
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        accessToken = prefs?.getString(KEY_ACCESS_TOKEN, "") ?: ""
    }

    fun isSignedIn(): Boolean = accessToken.isNotEmpty()

    fun currentUserId(): String {
        if (accessToken.isEmpty()) return ""
        return try {
            val parts = accessToken.split(".")
            if (parts.size < 2) return ""
            val payload = android.util.Base64.decode(parts[1], android.util.Base64.URL_SAFE)
            val json = String(payload)
            gson.fromJson(json, Map::class.java)["sub"]?.toString() ?: ""
        } catch (_: Exception) { "" }
    }

    fun saveCredentials(email: String, password: String) {
        prefs?.edit()?.apply {
            putString(KEY_SAVED_EMAIL, email)
            putString(KEY_SAVED_PASSWORD, password)
            apply()
        }
    }

    fun getSavedEmail(): String = prefs?.getString(KEY_SAVED_EMAIL, "") ?: ""
    fun getSavedPassword(): String = prefs?.getString(KEY_SAVED_PASSWORD, "") ?: ""

    suspend fun signUp(email: String, password: String, displayName: String) {
        withContext(Dispatchers.IO) {
            val body = gson.toJson(mapOf("email" to email, "password" to password))
            val request = buildRequest("auth/v1/signup")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: throw Exception("注册请求失败")
            if (!response.isSuccessful) throw Exception(parseError(responseBody))
            val result = gson.fromJson(responseBody, Map::class.java)
            saveSession(result)

            val profileBody = gson.toJson(mapOf(
                "user_id" to currentUserId(), "display_name" to displayName,
                "email" to email, "family_id" to ""
            ))
            val profileRequest = buildRequest("rest/v1/user_profiles")
                .post(profileBody.toRequestBody("application/json".toMediaType()))
                .header("Prefer", "return=minimal")
                .build()
            client.newCall(profileRequest).execute()
        }
    }

    suspend fun signIn(email: String, password: String) {
        withContext(Dispatchers.IO) {
            val body = gson.toJson(mapOf("email" to email, "password" to password))
            val request = buildRequest("auth/v1/token?grant_type=password")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: throw Exception("登录请求失败")
            if (!response.isSuccessful) throw Exception(parseError(responseBody))
            val result = gson.fromJson(responseBody, Map::class.java)
            saveSession(result)
        }
    }

    fun signOut() {
        accessToken = ""
        prefs?.edit()?.remove(KEY_ACCESS_TOKEN)?.remove(KEY_REFRESH_TOKEN)?.apply()
    }

    private fun saveSession(result: Map<*, *>) {
        accessToken = result["access_token"]?.toString() ?: ""
        val refreshToken = result["refresh_token"]?.toString() ?: ""
        prefs?.edit()?.apply {
            putString(KEY_ACCESS_TOKEN, accessToken)
            putString(KEY_REFRESH_TOKEN, refreshToken)
            apply()
        }
    }

    suspend fun getUserProfile(uid: String): Map<String, Any?>? {
        return withContext(Dispatchers.IO) {
            val request = buildRequest("rest/v1/user_profiles?user_id=eq.$uid&limit=1")
                .get().build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext null
            @Suppress("UNCHECKED_CAST")
            val list = gson.fromJson(body, object : TypeToken<List<Map<String, Any?>>>() {}.type) as? List<Map<String, Any?>>
            list?.firstOrNull()
        }
    }

    suspend fun createFamilyGroup(name: String, creatorId: String, creatorName: String, customCode: String? = null): Map<String, Any?> {
        return withContext(Dispatchers.IO) {
            val inviteCode = if (!customCode.isNullOrEmpty()) {
                if (!isInviteCodeAvailableInternal(customCode)) throw Exception("该邀请码已被使用，请换一个")
                customCode
            } else {
                generateUniqueCode()
            }
            val body = gson.toJson(mapOf(
                "name" to name, "creator_id" to creatorId, "creator_name" to creatorName,
                "member_ids" to listOf(creatorId), "member_names" to listOf(creatorName),
                "invite_code" to inviteCode
            ))
            val request = buildRequest("rest/v1/family_groups")
                .post(body.toRequestBody("application/json".toMediaType()))
                .header("Prefer", "return=representation")
                .build()
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: throw Exception("创建失败")
            if (!response.isSuccessful) throw Exception(parseError(responseBody))
            @Suppress("UNCHECKED_CAST")
            val list = gson.fromJson(responseBody, object : TypeToken<List<Map<String, Any?>>>() {}.type) as? List<Map<String, Any?>>
            val family = list?.firstOrNull() ?: throw Exception("创建失败")

            val familyId = family["id"].toString()
            val updateBody = gson.toJson(mapOf("family_id" to familyId))
            val updateRequest = buildRequest("rest/v1/user_profiles?user_id=eq.$creatorId")
                .patch(updateBody.toRequestBody("application/json".toMediaType()))
                .header("Prefer", "return=minimal")
                .build()
            client.newCall(updateRequest).execute()
            family
        }
    }

    suspend fun deleteFamilyGroup(familyId: String) {
        withContext(Dispatchers.IO) {
            val deletePhotosRequest = buildRequest("rest/v1/photos?family_id=eq.$familyId")
                .delete().build()
            client.newCall(deletePhotosRequest).execute()

            val deleteGroupRequest = buildRequest("rest/v1/family_groups?id=eq.$familyId")
                .delete().build()
            client.newCall(deleteGroupRequest).execute()
        }
    }

    suspend fun joinFamilyGroup(inviteCode: String, userId: String, userName: String): Map<String, Any?> {
        return withContext(Dispatchers.IO) {
            val request = buildRequest("rest/v1/family_groups?invite_code=eq.$inviteCode&limit=1")
                .get().build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: throw Exception("查询失败")
            @Suppress("UNCHECKED_CAST")
            val list = gson.fromJson(body, object : TypeToken<List<Map<String, Any?>>>() {}.type) as? List<Map<String, Any?>>
            if (list.isNullOrEmpty()) throw Exception("邀请码无效，请检查后重试")

            val family = list[0]
            val familyId = family["id"].toString()
            @Suppress("UNCHECKED_CAST")
            val memberIds = (family["member_ids"] as? List<String>) ?: emptyList()
            if (memberIds.contains(userId)) return@withContext family

            val newIds = memberIds + userId
            @Suppress("UNCHECKED_CAST")
            val memberNames = (family["member_names"] as? List<String>) ?: emptyList()
            val newNames = memberNames + userName

            val updateBody = gson.toJson(mapOf("member_ids" to newIds, "member_names" to newNames))
            val updateRequest = buildRequest("rest/v1/family_groups?id=eq.$familyId")
                .patch(updateBody.toRequestBody("application/json".toMediaType()))
                .header("Prefer", "return=minimal")
                .build()
            client.newCall(updateRequest).execute()

            val profileBody = gson.toJson(mapOf("family_id" to familyId))
            val profileRequest = buildRequest("rest/v1/user_profiles?user_id=eq.$userId")
                .patch(profileBody.toRequestBody("application/json".toMediaType()))
                .header("Prefer", "return=minimal")
                .build()
            client.newCall(profileRequest).execute()

            val getRequest = buildRequest("rest/v1/family_groups?id=eq.$familyId&limit=1").get().build()
            val getResponse = client.newCall(getRequest).execute()
            val getBody = getResponse.body?.string() ?: return@withContext family
            @Suppress("UNCHECKED_CAST")
            val getList = gson.fromJson(getBody, object : TypeToken<List<Map<String, Any?>>>() {}.type) as? List<Map<String, Any?>>
            getList?.firstOrNull() ?: family
        }
    }

    suspend fun getMyFamilyGroups(uid: String): List<Map<String, Any?>> {
        return withContext(Dispatchers.IO) {
            try {
                val encodedValue = java.net.URLEncoder.encode("[\"$uid\"]", "UTF-8")
                val request = buildRequest("rest/v1/family_groups?member_ids=cs.$encodedValue&order=created_at.desc")
                    .get().build()
                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: return@withContext emptyList()
                if (!response.isSuccessful) return@withContext queryByCreatorId(uid)
                @Suppress("UNCHECKED_CAST")
                val result = gson.fromJson(body, object : TypeToken<List<Map<String, Any?>>>() {}.type) as? List<Map<String, Any?>> ?: emptyList()
                if (result.isEmpty()) queryByCreatorId(uid) else result
            } catch (_: Exception) {
                queryByCreatorId(uid)
            }
        }
    }

    private fun queryByCreatorId(uid: String): List<Map<String, Any?>> {
        val request = buildRequest("rest/v1/family_groups?creator_id=eq.$uid&order=created_at.desc")
            .get().build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: return emptyList()
        @Suppress("UNCHECKED_CAST")
        return gson.fromJson(body, object : TypeToken<List<Map<String, Any?>>>() {}.type) as? List<Map<String, Any?>> ?: emptyList()
    }

    suspend fun getFamilyGroup(familyId: String): Map<String, Any?> {
        return withContext(Dispatchers.IO) {
            val request = buildRequest("rest/v1/family_groups?id=eq.$familyId&limit=1")
                .get().build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: throw Exception("查询失败")
            @Suppress("UNCHECKED_CAST")
            val list = gson.fromJson(body, object : TypeToken<List<Map<String, Any?>>>() {}.type) as? List<Map<String, Any?>>
            list?.firstOrNull() ?: throw Exception("家庭组不存在")
        }
    }

    suspend fun uploadPhoto(familyId: String, file: File, description: String, uploaderId: String, uploaderName: String) {
        withContext(Dispatchers.IO) {
            val fileName = "photo_${System.currentTimeMillis()}.jpg"
            val uploadRequest = buildRequest("storage/v1/object/photos/$fileName")
                .post(file.readBytes().toRequestBody("image/jpeg".toMediaType()))
                .build()
            val uploadResponse = client.newCall(uploadRequest).execute()
            if (!uploadResponse.isSuccessful) {
                val err = uploadResponse.body?.string() ?: "上传失败"
                throw Exception(parseError(err))
            }

            val fileUrl = "$SUPABASE_URL/storage/v1/object/public/photos/$fileName"
            val photoBody = gson.toJson(mapOf(
                "family_id" to familyId, "uploader_id" to uploaderId,
                "uploader_name" to uploaderName, "file_url" to fileUrl,
                "description" to description, "upload_time" to System.currentTimeMillis()
            ))
            val insertRequest = buildRequest("rest/v1/photos")
                .post(photoBody.toRequestBody("application/json".toMediaType()))
                .header("Prefer", "return=minimal")
                .build()
            client.newCall(insertRequest).execute()
        }
    }

    suspend fun getPhotos(familyId: String): List<Map<String, Any?>> {
        return withContext(Dispatchers.IO) {
            val request = buildRequest("rest/v1/photos?family_id=eq.$familyId&order=upload_time.desc&limit=200")
                .get().build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext emptyList()
            @Suppress("UNCHECKED_CAST")
            gson.fromJson(body, object : TypeToken<List<Map<String, Any?>>>() {}.type) as? List<Map<String, Any?>> ?: emptyList()
        }
    }

    suspend fun isInviteCodeAvailable(code: String): Boolean {
        return withContext(Dispatchers.IO) { isInviteCodeAvailableInternal(code) }
    }

    private fun isInviteCodeAvailableInternal(code: String): Boolean {
        val request = buildRequest("rest/v1/family_groups?invite_code=eq.$code&limit=1&select=id")
            .get().build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: return false
        @Suppress("UNCHECKED_CAST")
        val list = gson.fromJson(body, object : TypeToken<List<Map<String, Any?>>>() {}.type) as? List<Map<String, Any?>>
        return list.isNullOrEmpty()
    }

    suspend fun inviteCodeLogin(code: String, displayName: String) {
        withContext(Dispatchers.IO) {
            val findRequest = buildPublicRequest("rest/v1/family_groups?invite_code=eq.$code&limit=1")
                .get().build()
            val findResponse = client.newCall(findRequest).execute()
            if (!findResponse.isSuccessful) throw Exception("查询邀请码失败（HTTP ${findResponse.code}）")
            val findBody = findResponse.body?.string() ?: throw Exception("查询失败")
            @Suppress("UNCHECKED_CAST")
            val groups = gson.fromJson(findBody, object : TypeToken<List<Map<String, Any?>>>() {}.type) as? List<Map<String, Any?>>
            if (groups.isNullOrEmpty()) throw Exception("邀请码无效，请检查后重试")

            val nameHash = kotlin.math.abs(displayName.hashCode()).toString()
            val email = "ic${code}${nameHash}@invite.fm"
            val password = "Ic${code}!"

            var loggedIn = false
            try {
                val signInBody = gson.toJson(mapOf("email" to email, "password" to password))
                val signInRequest = buildRequest("auth/v1/token?grant_type=password")
                    .post(signInBody.toRequestBody("application/json".toMediaType()))
                    .build()
                val signInResponse = client.newCall(signInRequest).execute()
                if (signInResponse.isSuccessful) {
                    val signInResponseBody = signInResponse.body?.string()
                    if (signInResponseBody != null) {
                        val result = gson.fromJson(signInResponseBody, Map::class.java)
                        saveSession(result)
                        loggedIn = true
                    }
                }
            } catch (_: Exception) {}

            if (!loggedIn) {
                val signUpBody = gson.toJson(mapOf("email" to email, "password" to password))
                val signUpRequest = buildRequest("auth/v1/signup")
                    .post(signUpBody.toRequestBody("application/json".toMediaType()))
                    .build()
                val signUpResponse = client.newCall(signUpRequest).execute()
                val signUpResponseBody = signUpResponse.body?.string() ?: throw Exception("注册失败")
                if (!signUpResponse.isSuccessful) throw Exception("邀请码登录失败：${parseError(signUpResponseBody)}")
                val result = gson.fromJson(signUpResponseBody, Map::class.java)
                saveSession(result)

                val uid = currentUserId()
                val profileBody = gson.toJson(mapOf(
                    "user_id" to uid, "display_name" to displayName,
                    "email" to email, "family_id" to ""
                ))
                val profileRequest = buildRequest("rest/v1/user_profiles")
                    .post(profileBody.toRequestBody("application/json".toMediaType()))
                    .header("Prefer", "return=minimal")
                    .build()
                client.newCall(profileRequest).execute()
            }

            val uid = currentUserId()
            if (uid.isEmpty()) throw Exception("登录异常，请重试")
            joinFamilyGroup(code, uid, displayName)
        }
    }

    suspend fun checkForUpdate(currentVersionCode: Int): Map<String, String>? {
        return withContext(Dispatchers.IO) {
            try {
                val request = buildPublicRequest("rest/v1/app_versions?order=created_at.desc&limit=1")
                    .get().build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
                val body = response.body?.string() ?: return@withContext null
                @Suppress("UNCHECKED_CAST")
                val list = gson.fromJson(body, object : TypeToken<List<Map<String, Any?>>>() {}.type) as? List<Map<String, Any?>>
                val latest = list?.firstOrNull() ?: return@withContext null
                val latestCode = (latest["version_code"] as? Number)?.toInt() ?: 0
                if (latestCode > currentVersionCode) {
                    mapOf(
                        "version_name" to (latest["version_name"]?.toString() ?: ""),
                        "download_url" to (latest["download_url"]?.toString() ?: ""),
                        "release_notes" to (latest["release_notes"]?.toString() ?: "")
                    )
                } else null
            } catch (e: Exception) { throw e }
        }
    }

    suspend fun getLatestVersionInfo(): Map<String, String>? {
        return withContext(Dispatchers.IO) {
            try {
                val request = buildPublicRequest("rest/v1/app_versions?order=created_at.desc&limit=1")
                    .get().build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
                val body = response.body?.string() ?: return@withContext null
                @Suppress("UNCHECKED_CAST")
                val list = gson.fromJson(body, object : TypeToken<List<Map<String, Any?>>>() {}.type) as? List<Map<String, Any?>>
                val latest = list?.firstOrNull() ?: return@withContext null
                mapOf(
                    "version_name" to (latest["version_name"]?.toString() ?: ""),
                    "download_url" to (latest["download_url"]?.toString() ?: ""),
                    "release_notes" to (latest["release_notes"]?.toString() ?: "")
                )
            } catch (e: Exception) { throw e }
        }
    }

    private fun buildPublicRequest(path: String) = Request.Builder()
        .url("$SUPABASE_URL/$path")
        .header("apikey", SUPABASE_KEY)
        .header("Authorization", "Bearer $SUPABASE_KEY")
        .header("Content-Type", "application/json")

    private fun buildRequest(path: String) = Request.Builder()
        .url("$SUPABASE_URL/$path")
        .header("apikey", SUPABASE_KEY)
        .header("Authorization", "Bearer ${if (path.startsWith("auth")) SUPABASE_KEY else accessToken}")
        .header("Content-Type", "application/json")

    private fun parseError(body: String): String {
        return try {
            val error = gson.fromJson(body, Map::class.java)
            error["msg"]?.toString() ?: error["message"]?.toString() ?: error["error_description"]?.toString() ?: "操作失败"
        } catch (_: Exception) { "操作失败" }
    }

    private fun generateUniqueCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        repeat(20) {
            val code = (1..6).map { chars.random() }.joinToString("")
            if (isInviteCodeAvailableInternal(code)) return code
        }
        return (1..6).map { chars.random() }.joinToString("")
    }
}
