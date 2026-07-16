package com.skip.app

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken

internal data class PopupRule(
    @SerializedName("id") val id: String = "",
    @SerializedName("action") val action: String = "",
    @SerializedName("delay_popup") val delayMs: Long = 0L,
    @SerializedName("times") val times: Int? = null,
)

internal data class AppPopupRules(
    @SerializedName("popup_rules") val rules: List<PopupRule> = emptyList(),
    @SerializedName("click_way_popup") val clickWay: Int = 0,
    @SerializedName("search_times_popup") val searchTimes: Int? = null,
    @SerializedName("times") val times: Int? = null,
    @SerializedName("unite_popup_rules") val uniteRules: Boolean = false,
    @SerializedName("ltt_service") val serviceRule: Boolean = false,
)

internal data class PopupRuleSummary(
    val appCount: Int,
    val ruleCount: Int,
)

internal object PopupRuleRepository {
    private const val TAG = "PopupRuleRepository"
    private const val ASSET_NAME = "popup_rules.json"
    private val gson = Gson()
    private val trailingCommaRegex = Regex(",\\s*([}\\]])")

    @Volatile
    private var cachedRules: Map<Int, AppPopupRules>? = null

    fun rulesFor(context: Context, packageName: String): AppPopupRules? {
        return load(context)[packageName.hashCode()]
    }

    fun summary(context: Context): PopupRuleSummary {
        val rules = load(context)
        return PopupRuleSummary(
            appCount = rules.size,
            ruleCount = rules.values.sumOf { it.rules.size },
        )
    }

    fun preload(context: Context) {
        load(context)
    }

    private fun load(context: Context): Map<Int, AppPopupRules> {
        cachedRules?.let { return it }
        return synchronized(this) {
            cachedRules?.let { return@synchronized it }
            val loaded = runCatching { parse(context) }
                .onFailure { Log.e(TAG, "Unable to load popup rules", it) }
                .getOrDefault(emptyMap())
            cachedRules = loaded
            loaded
        }
    }

    private fun parse(context: Context): Map<Int, AppPopupRules> {
        val outerType = object : TypeToken<List<Map<String, String>>>() {}.type
        val outer: List<Map<String, String>> = context.assets.open(ASSET_NAME)
            .bufferedReader(Charsets.UTF_8)
            .use { reader -> gson.fromJson(reader, outerType) }

        return buildMap {
            for (entry in outer) {
                val (hashText, encodedRules) = entry.entries.firstOrNull() ?: continue
                val appHash = hashText.toIntOrNull() ?: continue
                val normalizedJson = encodedRules.replace(trailingCommaRegex, "$1")
                val ruleSet = runCatching {
                    gson.fromJson(normalizedJson, AppPopupRules::class.java)
                }.getOrNull() ?: continue
                put(
                    appHash,
                    ruleSet.copy(
                        rules = ruleSet.rules.filter { it.id.isNotBlank() && it.action.isNotBlank() }
                    )
                )
            }
        }
    }
}
