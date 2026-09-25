package io.github.antonkulaga.glucowatch.data

import glucowatch.core.GlucoseUnit
import glucowatch.core.NightscoutApi
import glucowatch.core.Region
import io.github.antonkulaga.glucowatch.BuildConfig

/**
 * Developer defaults from the project's `.env`, compiled into debug builds only
 * (release builds get empty strings, so [present] is false there).
 */
internal object BuildDefaults {
    private val username = BuildConfig.DEV_DEXCOM_USERNAME
    private val password = BuildConfig.DEV_DEXCOM_PASSWORD
    private val region = BuildConfig.DEV_DEXCOM_REGION
    private val nightscoutUrl = BuildConfig.DEV_NIGHTSCOUT_URL
    private val nightscoutToken = BuildConfig.DEV_NIGHTSCOUT_TOKEN
    private val nightscoutApi = BuildConfig.DEV_NIGHTSCOUT_API
    private val source = BuildConfig.DEV_GLUCOWATCH_SOURCE
    private val unit = BuildConfig.DEV_GLUCOWATCH_UNIT
    private val prediction = BuildConfig.DEV_GLUCOWATCH_PREDICTION

    private val all get() = listOf(username, password, region, nightscoutUrl, nightscoutToken, nightscoutApi, source, unit, prediction)

    val present get() = all.any { it.isNotEmpty() }

    /** Changes whenever `.env` changes, so a rebuild with new values is applied again. */
    val fingerprint get() = all.joinToString("\u0000").hashCode().toString(16)

    /** GLUCOWATCH_SOURCE wins; without it a Dexcom login picks Share, and a Nightscout address alone picks Nightscout. */
    fun applyTo(s: Settings): Settings = s.copy(
        source = when {
            source.isNotEmpty() -> DataSource.valueOf(source.uppercase())
            username.isNotEmpty() && password.isNotEmpty() -> DataSource.SHARE
            nightscoutUrl.isNotEmpty() -> DataSource.NIGHTSCOUT
            else -> s.source
        },
        username = username.ifEmpty { s.username },
        password = password.ifEmpty { s.password },
        region = region.takeIf { it.isNotEmpty() }?.let(Region::parse) ?: s.region,
        nightscoutUrl = nightscoutUrl.ifEmpty { s.nightscoutUrl },
        nightscoutToken = nightscoutToken.ifEmpty { s.nightscoutToken },
        nightscoutApi = nightscoutApi.takeIf { it.isNotEmpty() }?.let(NightscoutApi::parse) ?: s.nightscoutApi,
        unit = unit.takeIf { it.isNotEmpty() }?.let(GlucoseUnit::parse) ?: s.unit,
        predictionEnabled = prediction.lowercase().toBooleanStrictOrNull() ?: s.predictionEnabled,
    )
}
