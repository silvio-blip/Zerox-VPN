package com.example.ads

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

enum class AdMode {
    PRODUCTION,
    TEST
}

object RewardedAdManager {
    private const val TAG = "RewardedAdManager"

    // =========================================================================
    // IDENTIFICADORES DO GOOGLE ADMOB
    // =========================================================================
    const val APP_ID = "ca-app-pub-6932127476232839~4982913996"
    const val PRODUCTION_AD_UNIT_ID = "ca-app-pub-6932127476232839/1397350216"
    const val TEST_AD_UNIT_ID = "ca-app-pub-3940256099942544/5224354917"

    // =========================================================================
    // PARÂMETROS PRINCIPAIS DE CONTROLO DO COMPORTAMENTO
    // 1. AD_MODE: 'production' | 'test' (Padrão: PRODUCTION)
    // 2. FALLBACK_TO_TEST_ON_FAIL: true | false (Padrão: true)
    // =========================================================================
    var AD_MODE: AdMode = AdMode.PRODUCTION
    var FALLBACK_TO_TEST_ON_FAIL: Boolean = true

    fun setAdMode(mode: String) {
        AD_MODE = if (mode.equals("test", ignoreCase = true)) AdMode.TEST else AdMode.PRODUCTION
        Log.i(TAG, "[AdMob] AD_MODE definido para: $AD_MODE")
    }

    val activeAdUnitId: String
        get() = when (AD_MODE) {
            AdMode.PRODUCTION -> PRODUCTION_AD_UNIT_ID
            AdMode.TEST -> TEST_AD_UNIT_ID
        }

    @Volatile
    private var rewardedAd: RewardedAd? = null

    @Volatile
    private var loadedAdUnitId: String? = null

    @Volatile
    var lastErrorDetail: String? = null
        private set

    private val isLoading = AtomicBoolean(false)
    private val isInitialized = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    @SuppressLint("HardwareIds")
    fun initialize(context: Context) {
        val appContext = context.applicationContext
        runOnMainThread {
            if (isInitialized.compareAndSet(false, true)) {
                try {
                    val testDeviceIds = mutableListOf(AdRequest.DEVICE_ID_EMULATOR)
                    runCatching {
                        val androidId = Settings.Secure.getString(
                            appContext.contentResolver,
                            Settings.Secure.ANDROID_ID
                        )
                        if (!androidId.isNullOrBlank()) {
                            val digest = MessageDigest.getInstance("MD5")
                                .digest(androidId.toByteArray())
                            val hash = digest.joinToString("") { "%02X".format(it) }
                            testDeviceIds.add(hash)
                        }
                    }
                    val configuration = RequestConfiguration.Builder()
                        .setTestDeviceIds(testDeviceIds)
                        .build()
                    MobileAds.setRequestConfiguration(configuration)

                    Log.i(
                        TAG,
                        "[AdMob] Inicializando SDK AdMob -> AD_MODE=$AD_MODE | Bloco inicial=$activeAdUnitId | FALLBACK_TO_TEST_ON_FAIL=$FALLBACK_TO_TEST_ON_FAIL"
                    )

                    MobileAds.initialize(appContext) { initializationStatus ->
                        val adapters = initializationStatus.adapterStatusMap.entries.joinToString("; ") { (name, st) ->
                            "$name=${st.initializationState}"
                        }
                        Log.i(TAG, "[AdMob] SDK AdMob inicializado com sucesso. Adaptadores: [$adapters]")
                        preload(appContext)
                    }
                } catch (e: Exception) {
                    isInitialized.set(false)
                    val msg = "Erro ao inicializar SDK AdMob: ${e.javaClass.simpleName}: ${e.message}"
                    lastErrorDetail = msg
                    Log.e(TAG, "[AdMob] $msg", e)
                }
            } else {
                preload(appContext)
            }
        }
    }

    /**
     * Pré-carrega um anúncio premiado no arranque ou logo após cada exibição/falha.
     */
    fun preload(context: Context) {
        val appContext = context.applicationContext
        runOnMainThread {
            if (rewardedAd != null || isLoading.get()) {
                Log.d(TAG, "[AdMob] preload() ignorado: anúncio já pronto ($loadedAdUnitId) ou carregamento ativo.")
                return@runOnMainThread
            }
            isLoading.set(true)
            lastErrorDetail = null
            val targetUnit = activeAdUnitId
            loadInternal(appContext, targetUnit, allowFallback = (AD_MODE == AdMode.PRODUCTION))
        }
    }

    private fun loadInternal(
        appContext: Context,
        adUnitId: String,
        allowFallback: Boolean
    ) {
        val isTestUnit = (adUnitId == TEST_AD_UNIT_ID)
        Log.i(
            TAG,
            "[AdMob] Carregando anúncio premiado... Bloco: $adUnitId (${if (isTestUnit) "TESTE" else "PRODUÇÃO"}) | AD_MODE: $AD_MODE | Fallback permitido: $allowFallback"
        )
        val adRequest = AdRequest.Builder().build()
        RewardedAd.load(
            appContext,
            adUnitId,
            adRequest,
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    rewardedAd = ad
                    loadedAdUnitId = adUnitId
                    lastErrorDetail = null
                    isLoading.set(false)
                    Log.i(
                        TAG,
                        "[AdMob] Anúncio carregado com SUCESSO! Tipo: ${if (isTestUnit) "TESTE" else "PRODUÇÃO"} | Bloco: $adUnitId"
                    )
                }

                override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                    val detail = buildLoadErrorDetail(adUnitId, loadAdError)
                    Log.e(
                        TAG,
                        "[AdMob] Falha ao carregar anúncio. Bloco: $adUnitId | Código: ${loadAdError.code} | Mensagem: ${loadAdError.message} | Domínio: ${loadAdError.domain}"
                    )

                    // Se estiver em modo PRODUÇÃO e o bloco de produção falhar (No fill, rede, etc.)
                    if (AD_MODE == AdMode.PRODUCTION && adUnitId == PRODUCTION_AD_UNIT_ID && allowFallback) {
                        if (FALLBACK_TO_TEST_ON_FAIL) {
                            Log.w(
                                TAG,
                                "[AdMob] Bloco de produção sem inventário (${loadAdError.code}: ${loadAdError.message}). FALLBACK_TO_TEST_ON_FAIL=true -> Alternando automaticamente para o bloco de teste oficial ($TEST_AD_UNIT_ID)..."
                            )
                            loadInternal(appContext, TEST_AD_UNIT_ID, allowFallback = false)
                            return
                        } else {
                            Log.w(
                                TAG,
                                "[AdMob] Bloco de produção falhou e FALLBACK_TO_TEST_ON_FAIL=false. Nenhuma contingência será solicitada."
                            )
                        }
                    }

                    rewardedAd = null
                    loadedAdUnitId = null
                    lastErrorDetail = detail
                    isLoading.set(false)
                }
            }
        )
    }

    fun isAdAvailable(): Boolean = rewardedAd != null

    /**
     * Exibe o anúncio premiado pré-carregado ou aguarda até 10 segundos pelo carregamento sem travar o botão.
     */
    fun showOrWaitAndReport(
        activity: Activity,
        onRewardEarned: () -> Unit,
        onDismissedWithoutReward: () -> Unit,
        onAdUnavailable: (errorDetail: String) -> Unit
    ) {
        runOnMainThread {
            val current = rewardedAd
            if (current != null) {
                displayLoadedAd(activity, current, onRewardEarned, onDismissedWithoutReward, onAdUnavailable)
                return@runOnMainThread
            }

            if (!isInitialized.get()) {
                initialize(activity)
            } else if (!isLoading.get()) {
                preload(activity)
            }

            var waitedMs = 0
            val checkInterval = 200L
            val maxWaitMs = 10000L

            fun check() {
                if (activity.isFinishing || activity.isDestroyed) {
                    return
                }
                val ad = rewardedAd
                if (ad != null) {
                    displayLoadedAd(activity, ad, onRewardEarned, onDismissedWithoutReward, onAdUnavailable)
                } else if (!isLoading.get()) {
                    val detail = lastErrorDetail
                        ?: "Nenhum anúncio disponível no momento (AD_MODE=$AD_MODE, bloco=$activeAdUnitId)."
                    Log.w(TAG, "[AdMob] Anúncio indisponível após tentativa de carregamento: $detail")
                    onAdUnavailable(detail)
                } else if (waitedMs >= maxWaitMs) {
                    val detail = lastErrorDetail
                        ?: "Tempo limite esgotado ao carregar anúncio do AdMob (${maxWaitMs}ms)."
                    Log.w(TAG, "[AdMob] $detail")
                    onAdUnavailable(detail)
                } else {
                    waitedMs += checkInterval.toInt()
                    mainHandler.postDelayed({ check() }, checkInterval)
                }
            }

            mainHandler.postDelayed({ check() }, checkInterval)
        }
    }

    private fun displayLoadedAd(
        activity: Activity,
        ad: RewardedAd,
        onRewardEarned: () -> Unit,
        onDismissedWithoutReward: () -> Unit,
        onAdUnavailable: (errorDetail: String) -> Unit
    ) {
        var userEarnedReward = false
        val currentAdUnit = loadedAdUnitId ?: activeAdUnitId
        val isTest = (currentAdUnit == TEST_AD_UNIT_ID)

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                Log.i(TAG, "[AdMob] Anúncio fechado pelo utilizador. Recompensa obtida: $userEarnedReward (Bloco: $currentAdUnit)")
                rewardedAd = null
                loadedAdUnitId = null
                preload(activity)
                if (userEarnedReward) {
                    onRewardEarned()
                } else {
                    onDismissedWithoutReward()
                }
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                val detail = "Erro ao exibir anúncio (Código ${adError.code} / ${adError.domain}): ${adError.message}"
                Log.e(TAG, "[AdMob] Falha na exibição: $detail")
                rewardedAd = null
                loadedAdUnitId = null
                lastErrorDetail = detail
                preload(activity)
                onAdUnavailable(detail)
            }

            override fun onAdShowedFullScreenContent() {
                Log.i(TAG, "[AdMob] Anúncio em exibição na tela. Tipo: ${if (isTest) "TESTE" else "PRODUÇÃO"} | Bloco: $currentAdUnit")
            }
        }

        try {
            ad.show(activity) { rewardItem ->
                Log.i(TAG, "[AdMob] Recompensa confirmada pelo SDK AdMob (${rewardItem.amount} ${rewardItem.type}). Liberando conexão VPN direta!")
                userEarnedReward = true
            }
        } catch (e: Exception) {
            val detail = "Exceção ao chamar show(): ${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, "[AdMob] $detail", e)
            rewardedAd = null
            loadedAdUnitId = null
            lastErrorDetail = detail
            preload(activity)
            onAdUnavailable(detail)
        }
    }

    private fun buildLoadErrorDetail(adUnitId: String, error: LoadAdError): String {
        val codeLabel = when (error.code) {
            AdRequest.ERROR_CODE_INTERNAL_ERROR -> "INTERNAL_ERROR"
            AdRequest.ERROR_CODE_INVALID_REQUEST -> "INVALID_REQUEST"
            AdRequest.ERROR_CODE_NETWORK_ERROR -> "NETWORK_ERROR"
            AdRequest.ERROR_CODE_NO_FILL -> "NO_FILL"
            AdRequest.ERROR_CODE_APP_ID_MISSING -> "APP_ID_MISSING"
            else -> "CODE_${error.code}"
        }
        return "Sem anúncio disponível no AdMob\n" +
            "• Código: ${error.code} ($codeLabel)\n" +
            "• Domínio: ${error.domain}\n" +
            "• Mensagem: ${error.message}\n" +
            "• Bloco: $adUnitId"
    }

    private fun runOnMainThread(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }
}
