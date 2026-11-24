package com.example.ads

import android.content.Context
import android.util.Log
import com.startapp.sdk.adsbase.StartAppSDK
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AdConsentState(
    val hasUserChoice: Boolean = false,
    val personalizedAdsGranted: Boolean = false,
    val consentTimestampMs: Long = 0L
)

/**
 * Gerencia o consentimento GDPR/LGPD e CCPA para a rede Start.io.
 */
object AdConsentManager {
    private const val TAG = "StartIoDebug"
    private const val PREFS_NAME = "zerox_ad_consent_prefs"
    private const val KEY_HAS_CHOICE = "has_user_choice"
    private const val KEY_PERSONALIZED_GRANTED = "personalized_ads_granted"
    private const val KEY_CONSENT_TIMESTAMP = "consent_timestamp_ms"

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

        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_HAS_CHOICE, true)
            .putBoolean(KEY_PERSONALIZED_GRANTED, personalizedGranted)
            .putLong(KEY_CONSENT_TIMESTAMP, now)
            .apply()

        runCatching {
            StartAppSDK.setUserConsent(appContext, "pas", now, personalizedGranted)
        }

        Log.i(
            TAG,
            "[Consent] Consentimento Start.io atualizado: hasChoice=true, personalized=$personalizedGranted"
        )
        return updated
    }
}
