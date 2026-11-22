package com.example.ads

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.google.ads.mediation.admob.AdMobAdapter
import com.google.android.gms.ads.AdRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AdConsentState(
    val hasUserChoice: Boolean = false,
    val personalizedAdsGranted: Boolean = false,
    val consentTimestampMs: Long = 0L
)

/**
 * Gerencia o consentimento GDPR/LGPD e CCPA de forma não bloqueante em SharedPreferences,
 * configurando parâmetros de anúncios não personalizados (NPA) para o Google AdMob.
 */
object AdConsentManager {
    private const val TAG = "AdMobDebug"
    private const val PREFS_NAME = "zerox_ad_consent_prefs"
    private const val KEY_HAS_CHOICE = "has_user_choice"
    private const val KEY_PERSONALIZED_GRANTED = "personalized_ads_granted"
    private const val KEY_CONSENT_TIMESTAMP = "consent_timestamp_ms"
    private const val KEY_IAB_US_PRIVACY = "IABUSPrivacy_String"

    private val _consentState = MutableStateFlow(AdConsentState())
    val consentState: StateFlow<AdConsentState> = _consentState.asStateFlow()

    fun syncState(context: Context): AdConsentState {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val state = AdConsentState(
            hasUserChoice = prefs.getBoolean(KEY_HAS_CHOICE, false),
            personalizedAdsGranted = prefs.getBoolean(KEY_PERSONALIZED_GRANTED, false),
            consentTimestampMs = prefs.getLong(KEY_CONSENT_TIMESTAMP, 0L)
        )
        _consentState.value = state
        return state
    }

    fun hasUserMadeChoice(context: Context): Boolean {
        return syncState(context).hasUserChoice
    }

    fun saveConsentChoice(context: Context, personalizedGranted: Boolean): AdConsentState {
        val appContext = context.applicationContext
        val now = System.currentTimeMillis()

        val updated = AdConsentState(
            hasUserChoice = true,
            personalizedAdsGranted = personalizedGranted,
            consentTimestampMs = now
        )
        _consentState.value = updated

        // Persistência assíncrona (apply) para nunca travar a thread de UI
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_HAS_CHOICE, true)
            .putBoolean(KEY_PERSONALIZED_GRANTED, personalizedGranted)
            .putLong(KEY_CONSENT_TIMESTAMP, now)
            .apply()

        // CCPA IAB compliance string: 1YNN (personalizado) vs 1YYN (não personalizado)
        val usPrivacyString = if (personalizedGranted) "1YNN" else "1YYN"
        appContext.getSharedPreferences("${appContext.packageName}_preferences", Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_IAB_US_PRIVACY, usPrivacyString)
            .apply()

        Log.i(
            TAG,
            "[Consent] Consentimento atualizado: hasChoice=true, personalized=$personalizedGranted, CCPA=$usPrivacyString"
        )
        return updated
    }

    /**
     * Constrói a requisição AdRequest do AdMob.
     * Caso o utilizador não tenha aceite personalização ou opte por não-personalizado,
     * injeta o parâmetro "npa" = "1" via AdMobAdapter Network Extras.
     */
    fun buildAdRequest(context: Context): AdRequest {
        val state = syncState(context)
        val builder = AdRequest.Builder()

        if (state.hasUserChoice && !state.personalizedAdsGranted) {
            val extras = Bundle().apply {
                putString("npa", "1")
            }
            builder.addNetworkExtrasBundle(AdMobAdapter::class.java, extras)
            Log.d(TAG, "[AdRequest] AdRequest configurado com anúncios NÃO personalizados (npa=1).")
        } else {
            Log.d(TAG, "[AdRequest] AdRequest configurado com anúncios padrão.")
        }

        return builder.build()
    }
}
