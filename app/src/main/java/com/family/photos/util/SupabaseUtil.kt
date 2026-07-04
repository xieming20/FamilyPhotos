package com.family.photos.util

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
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
        .addInterceptor(AuthRefreshInterceptor())
        .build()

    private val gson = Gson()
    private var accessToken: String = ""
    private var refreshToken: String = ""
    private var prefs: SharedPreferences? = null


    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        accessToken = prefs?.getString(KEY_ACCESS_TOKEN, "") ?: ""
        refreshToken = prefs?.getString(KEY_REFRESH_TOKEN, "") ?: ""
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
        refreshToken = ""
        prefs?.edit()?.remove(KEY_ACCESS_TOKEN)?.remove(KEY_REFRESH_TOKEN)?.apply()
    }

    private fun saveSession(result: Map<*, *>) {
        accessToken = result["access_token"]?.toString() ?: ""
        refreshToken = result["refresh_token"]?.toString() ?: refreshToken
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

    suspend fun updateHeartbeat(uid: String) {
        withContext(Dispatchers.IO) {
            try {
                val now = java.time.Instant.now().toString()
                val body = gson.toJson(mapOf("user_id" to uid, "last_active_at" to now))
                val request = buildRequest("rest/v1/user_profiles?user_id=eq.$uid")
                    .patch(body.toRequestBody("application/json".toMediaType()))
                    .header("Prefer", "return=minimal")
                    .build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful || response.body?.string()?.contains("\"count\":0") == true) {
                    val createBody = gson.toJson(mapOf("user_id" to uid, "display_name" to "用户", "last_active_at" to now))
                    val createRequest = buildRequest("rest/v1/user_profiles")
                        .post(createBody.toRequestBody("application/json".toMediaType()))
                        .header("Prefer", "return=minimal")
                        .build()
                    client.newCall(createRequest).execute()
                }
            } catch (_: Exception) {}
        }
    }

    suspend fun getMemberStatuses(memberIds: List<String>): Map<String, Long> {
        return withContext(Dispatchers.IO) {
            try {
                val idsParam = memberIds.joinToString(",") { "\"$it\"" }
                val encodedIds = java.net.URLEncoder.encode("[$idsParam]", "UTF-8")
                val request = buildPublicRequest("rest/v1/user_profiles?user_id=in.$encodedIds&select=user_id,last_active_at")
                    .get().build()
                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: return@withContext emptyMap()
                @Suppress("UNCHECKED_CAST")
                val list = gson.fromJson(body, object : TypeToken<List<Map<String, Any?>>>() {}.type) as? List<Map<String, Any?>> ?: return@withContext emptyMap()
                val result = mutableMapOf<String, Long>()
                for (item in list) {
                    val userId = item["user_id"]?.toString() ?: continue
                    val lastActive = item["last_active_at"]?.toString() ?: ""
                    val timestamp = try {
                        java.time.Instant.parse(lastActive).toEpochMilli()
                    } catch (_: Exception) { 0L }
                    result[userId] = timestamp
                }
                result
            } catch (_: Exception) { emptyMap() }
        }
    }

    suspend fun createFamilyGroup(name: String, creatorId: String, creatorName: String): Map<String, Any?> {
        return withContext(Dispatchers.IO) {
            val body = gson.toJson(mapOf(
                "name" to name, "creator_id" to creatorId, "creator_name" to creatorName,
                "member_ids" to listOf(creatorId), "member_names" to listOf(creatorName)
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

    suspend fun updateFamilyGroup(familyId: String, name: String? = null) {
        withContext(Dispatchers.IO) {
            val fields = mutableMapOf<String, Any>()
            if (name != null) fields["name"] = name
            if (fields.isEmpty()) return@withContext
            val body = gson.toJson(fields)
            val request = buildRequest("rest/v1/family_groups?id=eq.$familyId")
                .patch(body.toRequestBody("application/json".toMediaType()))
                .header("Prefer", "return=minimal")
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) throw Exception("更新失败（HTTP ${response.code}）")
        }
    }

    suspend fun removeMember(familyId: String, memberId: String, memberName: String) {
        withContext(Dispatchers.IO) {
            val request = buildRequest("rest/v1/family_groups?id=eq.$familyId&limit=1")
                .get().build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: throw Exception("查询失败")
            @Suppress("UNCHECKED_CAST")
            val list = gson.fromJson(body, object : TypeToken<List<Map<String, Any?>>>() {}.type) as? List<Map<String, Any?>>
            val family = list?.firstOrNull() ?: throw Exception("家庭组不存在")

            @Suppress("UNCHECKED_CAST")
            val memberIds = ((family["member_ids"] as? List<String>) ?: emptyList()).filter { it != memberId }
            @Suppress("UNCHECKED_CAST")
            val memberNames = ((family["member_names"] as? List<String>) ?: emptyList()).filter { it != memberName }

            val updateBody = gson.toJson(mapOf("member_ids" to memberIds, "member_names" to memberNames))
            val updateRequest = buildRequest("rest/v1/family_groups?id=eq.$familyId")
                .patch(updateBody.toRequestBody("application/json".toMediaType()))
                .header("Prefer", "return=minimal")
                .build()
            val updateResponse = client.newCall(updateRequest).execute()
            if (!updateResponse.isSuccessful) throw Exception("移除成员失败（HTTP ${updateResponse.code}）")

            val profileBody = gson.toJson(mapOf("family_id" to ""))
            val profileRequest = buildRequest("rest/v1/user_profiles?user_id=eq.$memberId")
                .patch(profileBody.toRequestBody("application/json".toMediaType()))
                .header("Prefer", "return=minimal")
                .build()
            client.newCall(profileRequest).execute()
        }
    }

    suspend fun updateMemberName(familyId: String, memberId: String, oldName: String, newName: String) {
        withContext(Dispatchers.IO) {
            val request = buildRequest("rest/v1/family_groups?id=eq.$familyId&limit=1")
                .get().build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: throw Exception("查询失败")
            @Suppress("UNCHECKED_CAST")
            val list = gson.fromJson(body, object : TypeToken<List<Map<String, Any?>>>() {}.type) as? List<Map<String, Any?>>
            val family = list?.firstOrNull() ?: throw Exception("家庭组不存在")

            @Suppress("UNCHECKED_CAST")
            val memberNames = (family["member_names"] as? MutableList<String>) ?: mutableListOf()
            val newNames = memberNames.map { if (it == oldName) newName else it }

            val updateBody = gson.toJson(mapOf("member_names" to newNames))
            val updateRequest = buildRequest("rest/v1/family_groups?id=eq.$familyId")
                .patch(updateBody.toRequestBody("application/json".toMediaType()))
                .header("Prefer", "return=minimal")
                .build()
            val updateResponse = client.newCall(updateRequest).execute()
            if (!updateResponse.isSuccessful) throw Exception("修改昵称失败（HTTP ${updateResponse.code}）")

            val profileBody = gson.toJson(mapOf("display_name" to newName))
            val profileRequest = buildRequest("rest/v1/user_profiles?user_id=eq.$memberId")
                .patch(profileBody.toRequestBody("application/json".toMediaType()))
                .header("Prefer", "return=minimal")
                .build()
            client.newCall(profileRequest).execute()
        }
    }

    suspend fun joinFamilyGroup(familyId: String, userId: String, userName: String): Map<String, Any?> {
        return withContext(Dispatchers.IO) {
            val request = buildRequest("rest/v1/family_groups?id=eq.$familyId&limit=1")
                .get().build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: throw Exception("查询失败")
            @Suppress("UNCHECKED_CAST")
            val list = gson.fromJson(body, object : TypeToken<List<Map<String, Any?>>>() {}.type) as? List<Map<String, Any?>>
            if (list.isNullOrEmpty()) throw Exception("家庭组不存在")

            val family = list[0]
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
                val body = response.body?.string() ?: return@withContext queryByFamilyId(uid)
                if (!response.isSuccessful) return@withContext queryByFamilyId(uid)
                @Suppress("UNCHECKED_CAST")
                val result = gson.fromJson(body, object : TypeToken<List<Map<String, Any?>>>() {}.type) as? List<Map<String, Any?>> ?: emptyList()
                if (result.isEmpty()) queryByFamilyId(uid) else result
            } catch (_: Exception) {
                queryByFamilyId(uid)
            }
        }
    }

    private suspend fun queryByFamilyId(uid: String): List<Map<String, Any?>> {
        val familyId = getMyFamilyId(uid) ?: return queryByCreatorId(uid)
        return try {
            val request = buildRequest("rest/v1/family_groups?id=eq.$familyId&limit=1")
                .get().build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return queryByCreatorId(uid)
            @Suppress("UNCHECKED_CAST")
            val list = gson.fromJson(body, object : TypeToken<List<Map<String, Any?>>>() {}.type) as? List<Map<String, Any?>>
            list ?: queryByCreatorId(uid)
        } catch (_: Exception) {
            queryByCreatorId(uid)
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

    suspend fun getMyFamilyId(uid: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val request = buildRequest("rest/v1/user_profiles?user_id=eq.$uid&limit=1&select=family_id")
                    .get().build()
                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: return@withContext null
                @Suppress("UNCHECKED_CAST")
                val list = gson.fromJson(body, object : TypeToken<List<Map<String, Any?>>>() {}.type) as? List<Map<String, Any?>>
                list?.firstOrNull()?.get("family_id")?.toString()?.takeIf { it.isNotEmpty() }
            } catch (_: Exception) { null }
        }
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

    suspend fun uploadPhoto(familyId: String, file: File, description: String, uploaderId: String, uploaderName: String, fileHash: String = "") {
        withContext(Dispatchers.IO) {
            val fileName = if (fileHash.isNotEmpty()) "photo_${fileHash}.jpg" else "photo_${System.currentTimeMillis()}.jpg"
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

    suspend fun deletePhoto(photoId: String, fileUrl: String) {
        withContext(Dispatchers.IO) {
            val fileName = fileUrl.substringAfterLast("/")
            try {
                val storageRequest = buildRequest("storage/v1/object/photos/$fileName")
                    .delete().build()
                client.newCall(storageRequest).execute()
            } catch (_: Exception) {}

            val dbRequest = buildRequest("rest/v1/photos?id=eq.$photoId")
                .delete().build()
            val response = client.newCall(dbRequest).execute()
            if (!response.isSuccessful) throw Exception("删除失败（HTTP ${response.code}）")
        }
    }

    suspend fun getExistingPhotoUrls(familyId: String): Set<String> {
        return withContext(Dispatchers.IO) {
            try {
                val request = buildRequest("rest/v1/photos?family_id=eq.$familyId&select=file_url")
                    .get().build()
                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: return@withContext emptySet()
                @Suppress("UNCHECKED_CAST")
                val list = gson.fromJson(body, object : TypeToken<List<Map<String, Any?>>>() {}.type) as? List<Map<String, Any?>> ?: emptyList()
                list.mapNotNull { it["file_url"]?.toString() }.toSet()
            } catch (_: Exception) { emptySet() }
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

    suspend fun phoneLogin(phone: String, displayName: String): Map<String, Any?> {
        return withContext(Dispatchers.IO) {
            val name = displayName.ifEmpty { phone }
            val email = "ph${phone}@phone.fm"
            val password = "Ph${phone}!"

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
                if (!signUpResponse.isSuccessful) throw Exception("手机号登录失败：${parseError(signUpResponseBody)}")
                val result = gson.fromJson(signUpResponseBody, Map::class.java)
                saveSession(result)

                val uid = currentUserId()
                val profileBody = gson.toJson(mapOf(
                    "user_id" to uid, "display_name" to name,
                    "email" to email
                ))
                val profileRequest = buildRequest("rest/v1/user_profiles")
                    .post(profileBody.toRequestBody("application/json".toMediaType()))
                    .header("Prefer", "return=minimal")
                    .build()
                client.newCall(profileRequest).execute()
            }

            val uid = currentUserId()
            if (uid.isEmpty()) throw Exception("登录异常，请重试")

            val groups = getMyFamilyGroups(uid)
            if (groups.isNotEmpty()) {
                return@withContext groups[0]
            }

            emptyMap<String, Any?>()
        }
    }

    suspend fun addMemberByPhone(familyId: String, phone: String): String {
        return withContext(Dispatchers.IO) {
            val email = "ph${phone}@phone.fm"
            val password = "Ph${phone}!"

            var memberId = ""
            var memberName = phone

            val profileRequest = buildPublicRequest("rest/v1/user_profiles?email=eq.ph${phone}@phone.fm&limit=1")
                .get().build()
            val profileResponse = client.newCall(profileRequest).execute()
            val profileBody = profileResponse.body?.string() ?: throw Exception("查询用户失败")
            @Suppress("UNCHECKED_CAST")
            val profiles = gson.fromJson(profileBody, object : TypeToken<List<Map<String, Any?>>>() {}.type) as? List<Map<String, Any?>>
            val profile = profiles?.firstOrNull()

            if (profile != null) {
                memberId = profile["user_id"]?.toString() ?: ""
                memberName = profile["display_name"]?.toString() ?: phone
            } else {
                val signUpBody = gson.toJson(mapOf("email" to email, "password" to password))
                val signUpRequest = buildRequest("auth/v1/signup")
                    .post(signUpBody.toRequestBody("application/json".toMediaType()))
                    .build()
                val signUpResponse = client.newCall(signUpRequest).execute()
                val signUpResponseBody = signUpResponse.body?.string() ?: throw Exception("创建成员账号失败")
                if (!signUpResponse.isSuccessful) throw Exception("创建成员账号失败：${parseError(signUpResponseBody)}")
                val result = gson.fromJson(signUpResponseBody, Map::class.java)
                val newAccessToken = result["access_token"]?.toString() ?: ""
                val newRefreshToken = result["refresh_token"]?.toString() ?: ""

                val parts = newAccessToken.split(".")
                if (parts.size >= 2) {
                    val payload = android.util.Base64.decode(parts[1], android.util.Base64.URL_SAFE)
                    val json = String(payload)
                    memberId = gson.fromJson(json, Map::class.java)["sub"]?.toString() ?: ""
                }
                if (memberId.isEmpty()) throw Exception("创建成员账号异常")

                val profileCreateBody = gson.toJson(mapOf(
                    "user_id" to memberId, "display_name" to phone,
                    "email" to email, "family_id" to familyId
                ))
                val profileCreateRequest = buildRequest("rest/v1/user_profiles")
                    .post(profileCreateBody.toRequestBody("application/json".toMediaType()))
                    .header("Prefer", "return=minimal")
                    .build()
                client.newCall(profileCreateRequest).execute()
            }

            if (memberId.isEmpty()) throw Exception("无法获取成员信息")

            val familyRequest = buildRequest("rest/v1/family_groups?id=eq.$familyId&limit=1")
                .get().build()
            val familyResponse = client.newCall(familyRequest).execute()
            val familyBodyStr = familyResponse.body?.string() ?: throw Exception("查询家庭组失败")
            @Suppress("UNCHECKED_CAST")
            val familyList = gson.fromJson(familyBodyStr, object : TypeToken<List<Map<String, Any?>>>() {}.type) as? List<Map<String, Any?>>
            val family = familyList?.firstOrNull() ?: throw Exception("家庭组不存在")

            @Suppress("UNCHECKED_CAST")
            val memberIds = (family["member_ids"] as? List<String>) ?: emptyList()
            if (memberIds.contains(memberId)) throw Exception("该成员已在家庭组中")

            val newIds = memberIds + memberId
            @Suppress("UNCHECKED_CAST")
            val memberNames = (family["member_names"] as? List<String>) ?: emptyList()
            val newNames = memberNames + memberName

            val updateBody = gson.toJson(mapOf("member_ids" to newIds, "member_names" to newNames))
            val updateRequest = buildRequest("rest/v1/family_groups?id=eq.$familyId")
                .patch(updateBody.toRequestBody("application/json".toMediaType()))
                .header("Prefer", "return=minimal")
                .build()
            val updateResponse = client.newCall(updateRequest).execute()
            if (!updateResponse.isSuccessful) throw Exception("添加成员失败")

            if (profile == null) {
                // already set family_id during profile creation
            } else {
                val profileUpdateBody = gson.toJson(mapOf("family_id" to familyId))
                val profileUpdateRequest = buildRequest("rest/v1/user_profiles?user_id=eq.$memberId")
                    .patch(profileUpdateBody.toRequestBody("application/json".toMediaType()))
                    .header("Prefer", "return=minimal")
                    .build()
                client.newCall(profileUpdateRequest).execute()
            }

            memberName
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


    suspend fun getStorageUsage(): Map<String, Any?> {
        return withContext(Dispatchers.IO) {
            try {
                val listBody = gson.toJson(mapOf("prefix" to "", "limit" to 1000))
                val storageRequest = Request.Builder()
                    .url("$SUPABASE_URL/storage/v1/object/list/photos")
                    .header("apikey", SUPABASE_KEY)
                    .header("Authorization", "Bearer $SUPABASE_KEY")
                    .header("Content-Type", "application/json")
                    .post(listBody.toRequestBody("application/json".toMediaType()))
                    .build()
                val storageResponse = client.newCall(storageRequest).execute()
                if (!storageResponse.isSuccessful) throw Exception("存储查询失败（HTTP ${storageResponse.code}）")
                val storageBody = storageResponse.body?.string() ?: throw Exception("查询失败")
                @Suppress("UNCHECKED_CAST")
                val files = gson.fromJson(storageBody, object : TypeToken<List<Map<String, Any?>>>() {}.type) as? List<Map<String, Any?>> ?: emptyList()

                var totalSize = 0L
                var photoCount = 0
                var apkCount = 0
                var apkSize = 0L
                for (file in files) {
                    val metadata = file["metadata"] as? Map<String, Any?>
                    val size = (metadata?.get("size") as? Number)?.toLong() ?: 0L
                    val name = file["name"]?.toString() ?: ""
                    totalSize += size
                    if (name.endsWith(".apk")) {
                        apkCount++
                        apkSize += size
                    } else {
                        photoCount++
                    }
                }

                val photoRequest = buildPublicRequest("rest/v1/photos?select=id")
                    .get().build()
                val photoResponse = client.newCall(photoRequest).execute()
                val photoBody = photoResponse.body?.string() ?: "[]"
                @Suppress("UNCHECKED_CAST")
                val photoRecords = gson.fromJson(photoBody, object : TypeToken<List<Map<String, Any?>>>() {}.type) as? List<Map<String, Any?>> ?: emptyList()

                val familyRequest = buildPublicRequest("rest/v1/family_groups?select=id,name,member_names")
                    .get().build()
                val familyResponse = client.newCall(familyRequest).execute()
                val familyBody = familyResponse.body?.string() ?: "[]"
                @Suppress("UNCHECKED_CAST")
                val families = gson.fromJson(familyBody, object : TypeToken<List<Map<String, Any?>>>() {}.type) as? List<Map<String, Any?>> ?: emptyList()
                val familyCount = families.size
                val memberCount = families.sumOf { (it["member_names"] as? List<*>)?.size ?: 0 }

                val freeTierBytes = 1073741824L
                val availableSize = maxOf(0L, freeTierBytes - totalSize)
                val usagePercent = if (freeTierBytes > 0) (totalSize * 100 / freeTierBytes).toInt() else 0

                mapOf(
                    "total_size" to totalSize,
                    "available_size" to availableSize,
                    "total_capacity" to freeTierBytes,
                    "usage_percent" to usagePercent,
                    "photo_size" to (totalSize - apkSize),
                    "apk_size" to apkSize,
                    "photo_file_count" to photoCount,
                    "photo_record_count" to photoRecords.size,
                    "family_count" to familyCount,
                    "member_count" to memberCount,
                    "apk_count" to apkCount
                )
            } catch (e: Exception) {
                throw e
            }
        }
    }

    private fun syncRefreshToken(): Boolean {
        if (refreshToken.isEmpty()) return false
        try {
            val body = gson.toJson(mapOf("refresh_token" to refreshToken))
            val request = Request.Builder()
                .url("$SUPABASE_URL/auth/v1/token?grant_type=refresh_token")
                .header("apikey", SUPABASE_KEY)
                .header("Authorization", "Bearer $SUPABASE_KEY")
                .header("Content-Type", "application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            val response = OkHttpClient.Builder().build().newCall(request).execute()
            if (!response.isSuccessful) return false
            val responseBody = response.body?.string() ?: return false
            val result = gson.fromJson(responseBody, Map::class.java)
            val newAccessToken = result["access_token"]?.toString() ?: return false
            val newRefreshToken = result["refresh_token"]?.toString() ?: refreshToken
            accessToken = newAccessToken
            refreshToken = newRefreshToken
            prefs?.edit()?.apply {
                putString(KEY_ACCESS_TOKEN, accessToken)
                putString(KEY_REFRESH_TOKEN, refreshToken)
                apply()
            }
            return true
        } catch (_: Exception) { return false }
    }

    private class AuthRefreshInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
            val request = chain.request()
            val response = chain.proceed(request)
            if (response.code == 401) {
                val authHeader = request.header("Authorization") ?: ""
                if (authHeader.contains(SUPABASE_KEY) || authHeader.isEmpty()) return response
                response.close()
                val refreshed = syncRefreshToken()
                if (refreshed) {
                    val newRequest = request.newBuilder()
                        .header("Authorization", "Bearer ${accessToken}")
                        .build()
                    return chain.proceed(newRequest)
                }
            }
            return response
        }
    }
}
