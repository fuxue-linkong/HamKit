package com.example.hamkit.data.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 多模型配置的持久化。
 *
 * 只存非敏感的连接参数；每个 profile 的 API Key 由 [AiKeyStore] 按 id 槽位单独加密。
 * 首次加载会把旧版单模型配置迁移成 `default` profile，旧 Key 继续沿用（槽位为空）。
 */
class AiProfileStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 当前保存的全部 profile（顺序即展示顺序）。 */
    fun list(): List<AiModelProfile> {
        val raw = prefs.getString(KEY_PROFILES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                    add(decode(item, id))
                }
            }
        }.getOrDefault(emptyList())
    }

    fun activeId(): String = prefs.getString(KEY_ACTIVE, null)?.takeIf { it.isNotBlank() }
        ?: AiModelProfile.DEFAULT_ID

    /**
     * 迁移旧配置并返回当前 profile。
     *
     * 旧版本只有一份配置与一个 Key（槽位为空），迁移后 `default` profile 直接沿用旧 Key。
     */
    fun ensureMigrated(legacy: AiConfig, hasLegacyKey: Boolean): AiModelProfile {
        val existing = list()
        if (existing.isNotEmpty()) {
            return active(existing)
        }
        // 旧配置存在（或旧 Key 存在）时按旧值建 default；否则用默认值
        val profile = if (hasLegacyKey) {
            AiModelProfile.fromConfig(AiModelProfile.DEFAULT_ID, "默认", legacy)
        } else {
            AiModelProfile.fromConfig(AiModelProfile.DEFAULT_ID, "默认", legacy.copy())
        }
        saveAll(listOf(profile), profile.id)
        return profile
    }

    fun active(all: List<AiModelProfile> = list()): AiModelProfile {
        if (all.isEmpty()) {
            return AiModelProfile.fromConfig(
                AiModelProfile.DEFAULT_ID,
                "默认",
                AiConfig(),
            )
        }
        val id = activeId()
        return all.firstOrNull { it.id == id } ?: all.first()
    }

    fun setActive(id: String) {
        prefs.edit().putString(KEY_ACTIVE, id).apply()
    }

    fun upsert(profile: AiModelProfile) {
        val all = list().toMutableList()
        val value = profile.normalized()
        val index = all.indexOfFirst { it.id == value.id }
        if (index >= 0) all[index] = value else all += value
        saveAll(all, activeId().takeIf { active -> all.any { it.id == active } } ?: value.id)
    }

    /** 删除；不允许删掉最后一个 profile。 */
    fun delete(id: String): Boolean {
        val all = list()
        if (all.size <= 1) return false
        val remaining = all.filterNot { it.id == id }
        val nextActive = if (activeId() == id) remaining.first().id else activeId()
        saveAll(remaining, nextActive)
        return true
    }

    fun saveAll(profiles: List<AiModelProfile>, active: String) {
        val array = JSONArray()
        profiles.forEach { profile ->
            array.put(JSONObject().apply {
                put("id", profile.id)
                put("name", profile.name)
                put("preset", profile.preset.name)
                put("protocol", profile.protocol.name)
                put("baseUrl", profile.baseUrl)
                put("models", JSONArray().apply {
                    profile.models.forEach { entry ->
                        put(JSONObject().apply {
                            put("id", entry.id)
                            put("modelId", entry.modelId)
                            entry.displayName?.let { put("displayName", it) }
                        })
                    }
                })
                profile.selectedModelId?.let { put("selectedModelId", it) }
                put("temperature", profile.temperature.toDouble())
                put("maxOutputTokens", profile.maxOutputTokens)
            })
        }
        prefs.edit()
            .putString(KEY_PROFILES, array.toString())
            .putString(KEY_ACTIVE, active)
            .apply()
    }

    private fun decode(item: JSONObject, id: String): AiModelProfile {
        val preset = AiPreset.fromName(item.optString("preset").takeIf { it.isNotBlank() })
        val protocol = runCatching {
            AiProtocol.valueOf(item.optString("protocol", preset.protocol.name))
        }.getOrDefault(preset.protocol)
        // 兼容旧版：没有 models 时用 model 生成单条
        val models = item.optJSONArray("models")?.let { array ->
            buildList {
                for (index in 0 until array.length()) {
                    val entry = array.optJSONObject(index) ?: continue
                    val modelId = entry.optString("modelId").takeIf { it.isNotBlank() } ?: continue
                    add(
                        AiModelEntry(
                            id = entry.optString("id").takeIf { it.isNotBlank() } ?: "m$index",
                            modelId = modelId,
                            displayName = entry.optString("displayName").takeIf { it.isNotBlank() },
                        ),
                    )
                }
            }
        }.orEmpty().ifEmpty {
            listOf(
                AiModelEntry(
                    id = "m1",
                    modelId = item.optString("model").takeIf { it.isNotBlank() } ?: preset.defaultModel,
                ),
            )
        }
        return AiModelProfile(
            id = id,
            name = item.optString("name").ifBlank { "未命名" },
            preset = preset,
            protocol = protocol,
            baseUrl = item.optString("baseUrl").takeIf { it.isNotBlank() } ?: preset.baseUrl,
            models = models,
            selectedModelId = item.optString("selectedModelId").takeIf { it.isNotBlank() },
            temperature = item.optDouble("temperature", 0.6).toFloat(),
            maxOutputTokens = item.optInt("maxOutputTokens", 2048),
        ).normalized()
    }

    private companion object {
        const val PREFS_NAME = "ai_profiles"
        const val KEY_PROFILES = "profiles"
        const val KEY_ACTIVE = "active_id"
    }
}
