package com.example

import android.app.Application
import android.os.Build
import android.util.Log
import com.example.ads.AdConsentManager
import com.example.ads.RewardedAdManager
import java.io.File

class ZeroxApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        val processName = resolveProcessName()
        if (processName.endsWith(":vpn")) {
            return
        }

        val consentState = AdConsentManager.syncState(this)
        Log.i(
            "StartAppDebug",
            "[Application] Inicializando Start.io SDK no arranque (pacote=$packageName, consentimento=${consentState.hasUserChoice})."
        )
        // Inicializa o SDK antecipadamente sem pré-carregar anúncios antes do consentimento e sem Activity ativa.
        RewardedAdManager.initializeSdk(this)
    }

    private fun resolveProcessName(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return getProcessName() ?: packageName
        }
        return runCatching {
            File("/proc/self/cmdline").readText().trim { it <= ' ' || it == '\u0000' }
        }.getOrDefault(packageName)
    }
}
