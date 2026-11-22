package com.example.ads

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

sealed class AdLoadState {
    data object Idle : AdLoadState()
    data class Loading(val modeLabel: String) : AdLoadState()
    data class Ready(val modeLabel: String) : AdLoadState()
    data class Error(val detail: String) : AdLoadState()
}

/**
 * Gestor oficial do Google Mobile Ads (AdMob) em MODO DE PRODUÇÃO EXCLUSIVO.
 * Utiliza o formato Recompensado (Rewarded) configurado.
 */
object RewardedAdManager {
    private const val TAG = "AdMobDebug"

    // =========================================================================
    // GOOGLE ADMOB — IDS REAIS DE PRODUÇÃO
    // =========================================================================
    const val ADMOB_APP_ID = "ca-app-pub-6932127476232839~4982913996"
    const val ADMOB_REWARDED_AD_UNIT_ID = "ca-app-pub-6932127476232839/1397350216"

    private const val PRELOAD_RETRY_COOLDOWN_MS = 15_000L
    private const val IN_FLIGHT_WAIT_TIMEOUT_MS = 6_000L

    private val _adState = MutableStateFlow<AdLoadState>(AdLoadState.Idle)
    val adState: StateFlow<AdLoadState> = _adState.asStateFlow()

    private val _sdkInitialized = MutableStateFlow(false)
    val sdkInitialized: StateFlow<Boolean> = _sdkInitialized.asStateFlow()

    @Volatile
    private var cachedRewardedAd: RewardedAd? = null

    @Volatile
    private var activityRef: WeakReference<Activity>? = null

    @Volatile
    var lastErrorDetail: String? = null
        private set

    @Volatile
    private var lastAttemptTimestampMs: Long = 0L

    private val isSdkInitStarted = AtomicBoolean(false)
    private val isLoading = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingInitCallbacks = mutableListOf<() -> Unit>()

    private var activeLoadCallbacks: Pair<(() -> Unit)?, ((String) -> Unit)?>? = null
    private var pendingWaitTimeoutRunnable: Runnable? = null

    fun isConfigured(): Boolean {
        return ADMOB_REWARDED_AD_UNIT_ID.isNotBlank() &&
            !ADMOB_REWARDED_AD_UNIT_ID.contains("SUBSTITUIR") &&
            !ADMOB_REWARDED_AD_UNIT_ID.contains("3940256099942544")
    }

    fun updateActivity(activity: Activity) {
        activityRef = WeakReference(activity)
    }

    fun isAdReady(): Boolean {
        return cachedRewardedAd != null
    }

    fun invalidateCachedAd() {
        runOnMainThread {
            cachedRewardedAd = null
            lastErrorDetail = null
            lastAttemptTimestampMs = 0L
            cancelPendingWaitTimeout()
            activeLoadCallbacks = null
            if (!isLoading.get()) {
                _adState.value = AdLoadState.Idle
            }
            Log.i(TAG, "[Cache] Cache de anúncios AdMob invalidado.")
        }
    }

    fun initializeSdk(context: Context, onReady: (() -> Unit)? = null) {
        if (context is Activity) {
            activityRef = WeakReference(context)
        }
        val appContext = context.applicationContext

        runOnMainThread {
            if (_sdkInitialized.value) {
                onReady?.invoke()
                return@runOnMainThread
            }

            if (onReady != null) {
                pendingInitCallbacks.add(onReady)
            }

            if (!isSdkInitStarted.compareAndSet(false, true)) {
                return@runOnMainThread
            }

            if (!isConfigured()) {
                val msg = "IDs de produção do AdMob pendentes ou inválidos."
                lastErrorDetail = msg
                _adState.value = AdLoadState.Error(msg)
                Log.w(TAG, "[Init] $msg")
                return@runOnMainThread
            }

            try {
                Log.i(TAG, "[Init] Inicializando Google Mobile Ads SDK em modo de produção (Rewarded)...")
                MobileAds.initialize(appContext) { initStatus ->
                    runOnMainThread {
                        _sdkInitialized.value = true
                        Log.i(TAG, "[Init] Google Mobile Ads SDK inicializado com sucesso: ${initStatus.adapterStatusMap.keys}")
                        val callbacks = pendingInitCallbacks.toList()
                        pendingInitCallbacks.clear()
                        callbacks.forEach { it.invoke() }
                    }
                }
            } catch (e: Exception) {
                isSdkInitStarted.set(false)
                _sdkInitialized.value = false
                val msg = "Erro ao inicializar Google AdMob SDK: ${e.javaClass.simpleName}: ${e.message}"
                lastErrorDetail = msg
                _adState.value = AdLoadState.Error(msg)
                pendingInitCallbacks.clear()
                Log.e(TAG, "[Init] $msg", e)
            }
        }
    }

    fun preloadSilently(context: Context, force: Boolean = false) {
        if (context is Activity) {
            activityRef = WeakReference(context)
        }
        val appContext = context.applicationContext
        runOnMainThread {
            if (!isConfigured() || isAdReady() || isLoading.get()) {
                return@runOnMainThread
            }
            val now = System.currentTimeMillis()
            if (!force && lastErrorDetail != null && (now - lastAttemptTimestampMs) < PRELOAD_RETRY_COOLDOWN_MS) {
                return@runOnMainThread
            }
            loadAd(context = appContext, onLoaded = null, onFailed = null)
        }
    }

    fun loadAd(
        context: Context,
        onLoaded: (() -> Unit)? = null,
        onFailed: ((String) -> Unit)? = null
    ) {
        if (context is Activity) {
            activityRef = WeakReference(context)
        }
        val appContext = context.applicationContext

        runOnMainThread {
            if (!isConfigured()) {
                val err = "AdMob em modo de produção: IDs inválidos ou não configurados."
                lastErrorDetail = err
                _adState.value = AdLoadState.Error(err)
                onFailed?.invoke(err)
                return@runOnMainThread
            }

            if (!_sdkInitialized.value) {
                initializeSdk(appContext) {
                    loadAd(appContext, onLoaded, onFailed)
                }
                return@runOnMainThread
            }

            if (isAdReady()) {
                Log.i(TAG, "[Load] Anúncio AdMob Recompensado já pronto em cache.")
                onLoaded?.invoke()
                return@runOnMainThread
            }

            if (onLoaded != null || onFailed != null) {
                activeLoadCallbacks = Pair(onLoaded, onFailed)
            }

            if (!isLoading.compareAndSet(false, true)) {
                Log.d(TAG, "[Load] Carregamento AdMob já em andamento.")
                return@runOnMainThread
            }

            lastAttemptTimestampMs = System.currentTimeMillis()
            cachedRewardedAd = null
            lastErrorDetail = null
            _adState.value = AdLoadState.Loading("AdMob Recompensado")

            try {
                val adRequest = AdConsentManager.buildAdRequest(appContext)
                Log.i(TAG, "[Load] Solicitando RewardedAd (Unit ID: $ADMOB_REWARDED_AD_UNIT_ID)...")

                RewardedAd.load(
                    appContext,
                    ADMOB_REWARDED_AD_UNIT_ID,
                    adRequest,
                    object : RewardedAdLoadCallback() {
                        override fun onAdLoaded(rewardedAd: RewardedAd) {
                            runOnMainThread {
                                cancelPendingWaitTimeout()
                                cachedRewardedAd = rewardedAd
                                lastErrorDetail = null
                                isLoading.set(false)
                                _adState.value = AdLoadState.Ready("AdMob Recompensado Pronto")
                                Log.i(TAG, "[Load] RewardedAd carregado com sucesso.")
                                val cb = activeLoadCallbacks
                                activeLoadCallbacks = null
                                cb?.first?.invoke()
                            }
                        }

                        override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                            runOnMainThread {
                                cancelPendingWaitTimeout()
                                val reason = "Código ${loadAdError.code}: ${loadAdError.message}"
                                cachedRewardedAd = null
                                lastErrorDetail = reason
                                isLoading.set(false)
                                _adState.value = AdLoadState.Error(reason)
                                Log.e(TAG, "[Load] RewardedAd falhou: $reason")
                                val cb = activeLoadCallbacks
                                activeLoadCallbacks = null
                                cb?.second?.invoke(reason)
                            }
                        }
                    }
                )
            } catch (e: Exception) {
                runOnMainThread {
                    cancelPendingWaitTimeout()
                    val reason = "${e.javaClass.simpleName}: ${e.message}"
                    cachedRewardedAd = null
                    lastErrorDetail = reason
                    isLoading.set(false)
                    _adState.value = AdLoadState.Error(reason)
                    Log.e(TAG, "[Load] Exceção ao solicitar anúncio: $reason", e)
                    val cb = activeLoadCallbacks
                    activeLoadCallbacks = null
                    cb?.second?.invoke(reason)
                }
            }
        }
    }

    fun showRequiredAdOrBlock(
        activity: Activity,
        onAdClosedConnectVpn: () -> Unit,
        onBlocked: (String) -> Unit
    ) {
        activityRef = WeakReference(activity)
        runOnMainThread {
            if (!isConfigured()) {
                val msg = "Modo de Produção: configure os IDs reais do Google AdMob."
                Log.w(TAG, "[Connect] $msg")
                onBlocked(msg)
                return@runOnMainThread
            }

            val rewarded = cachedRewardedAd
            if (rewarded != null) {
                displayMandatoryRewardedAd(activity, rewarded, onAdClosedConnectVpn, onBlocked)
                return@runOnMainThread
            }

            Log.i(TAG, "[Connect] Sem anúncio em cache; carregando anúncio obrigatório antes da VPN...")
            scheduleWaitTimeout {
                if (activity.isFinishing || activity.isDestroyed) return@scheduleWaitTimeout
                activeLoadCallbacks = null
                Log.w(TAG, "[Connect] Tempo limite de anúncio esgotado. Conexão VPN bloqueada.")
                onBlocked("Anúncio indisponível no momento. Por favor, aguarde alguns segundos e tente novamente.")
            }

            loadAd(
                context = activity,
                onLoaded = {
                    cancelPendingWaitTimeout()
                    if (activity.isFinishing || activity.isDestroyed) return@loadAd
                    val readyRewarded = cachedRewardedAd
                    if (readyRewarded != null) {
                        displayMandatoryRewardedAd(activity, readyRewarded, onAdClosedConnectVpn, onBlocked)
                    } else {
                        Log.w(TAG, "[Connect] Anúncio não ficou pronto. Conexão bloqueada.")
                        onBlocked("Anúncio não carregou a tempo. Tente novamente.")
                    }
                },
                onFailed = { detail ->
                    cancelPendingWaitTimeout()
                    if (activity.isFinishing || activity.isDestroyed) return@loadAd
                    Log.w(TAG, "[Connect] Erro ao carregar anúncio obrigatório ($detail). Conexão bloqueada.")
                    onBlocked("Anúncio indisponível ($detail). A conexão VPN requer a exibição de um anúncio.")
                }
            )
        }
    }

    fun showLoadedAd(
        activity: Activity,
        onFinished: (Boolean, String) -> Unit
    ) {
        activityRef = WeakReference(activity)
        runOnMainThread {
            val rewarded = cachedRewardedAd
            if (rewarded == null) {
                onFinished(false, "Nenhum anúncio em cache.")
                return@runOnMainThread
            }

            cachedRewardedAd = null
            _adState.value = AdLoadState.Idle
            val completed = AtomicBoolean(false)

            rewarded.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    if (completed.compareAndSet(false, true)) {
                        preloadSilently(activity, force = true)
                        onFinished(true, "Anúncio Recompensado AdMob exibido e fechado com sucesso.")
                    }
                }

                override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                    if (completed.compareAndSet(false, true)) {
                        preloadSilently(activity, force = true)
                        onFinished(false, "Falha ao exibir Recompensado: ${adError.message}")
                    }
                }
            }

            try {
                rewarded.show(activity) { rewardItem ->
                    Log.i(TAG, "[Show] Recompensa concedida: ${rewardItem.amount} ${rewardItem.type}")
                }
            } catch (e: Exception) {
                if (completed.compareAndSet(false, true)) {
                    onFinished(false, "Erro ao exibir: ${e.message}")
                }
            }
        }
    }

    private fun displayMandatoryRewardedAd(
        activity: Activity,
        rewarded: RewardedAd,
        onAdClosedConnectVpn: () -> Unit,
        onBlocked: (String) -> Unit
    ) {
        cachedRewardedAd = null
        _adState.value = AdLoadState.Idle
        val callbackDelivered = AtomicBoolean(false)

        rewarded.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                if (!callbackDelivered.compareAndSet(false, true)) return
                Log.i(TAG, "[Show] Anúncio fechado pelo usuário. Iniciando túnel VPN com sucesso.")
                onAdClosedConnectVpn()
                preloadSilently(activity, force = true)
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                if (!callbackDelivered.compareAndSet(false, true)) return
                Log.e(TAG, "[Show] Falha ao renderizar anúncio Recompensado: ${adError.message}")
                lastErrorDetail = adError.message
                _adState.value = AdLoadState.Error(adError.message)
                preloadSilently(activity, force = true)
                onBlocked("Falha ao exibir o anúncio. Por favor, tente novamente.")
            }

            override fun onAdShowedFullScreenContent() {
                Log.i(TAG, "[Show] Anúncio Recompensado do AdMob exibido na tela.")
            }
        }

        try {
            rewarded.show(activity) { rewardItem ->
                Log.i(TAG, "[Show] Recompensa obtida: ${rewardItem.amount} ${rewardItem.type}")
            }
        } catch (e: Exception) {
            if (callbackDelivered.compareAndSet(false, true)) {
                Log.e(TAG, "[Show] Exceção ao abrir anúncio: ${e.message}", e)
                preloadSilently(activity, force = true)
                onBlocked("Não foi possível abrir o anúncio: ${e.message}")
            }
        }
    }

    private fun scheduleWaitTimeout(onTimeout: () -> Unit) {
        cancelPendingWaitTimeout()
        val runnable = Runnable {
            pendingWaitTimeoutRunnable = null
            onTimeout()
        }
        pendingWaitTimeoutRunnable = runnable
        mainHandler.postDelayed(runnable, IN_FLIGHT_WAIT_TIMEOUT_MS)
    }

    private fun cancelPendingWaitTimeout() {
        pendingWaitTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingWaitTimeoutRunnable = null
    }

    private fun runOnMainThread(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }
}
