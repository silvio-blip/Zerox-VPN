package com.example.ads

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.startapp.sdk.adsbase.Ad
import com.startapp.sdk.adsbase.StartAppAd
import com.startapp.sdk.adsbase.StartAppSDK
import com.startapp.sdk.adsbase.adlisteners.AdDisplayListener
import com.startapp.sdk.adsbase.adlisteners.AdEventListener
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
 * Gestor oficial do Start.io em MODO DE PRODUÇÃO EXCLUSIVO com estratégia de fallback imediato e timeout de 3s.
 */
object RewardedAdManager {
    private const val TAG = "StartIoDebug"

    // =========================================================================
    // START.IO — APP ID DE PRODUÇÃO
    // =========================================================================
    const val STARTAPP_APP_ID = "210992352"

    private const val PRELOAD_RETRY_COOLDOWN_MS = 15_000L
    private const val IN_FLIGHT_WAIT_TIMEOUT_MS = 3_000L // 3 seconds timeout requirement

    private val _adState = MutableStateFlow<AdLoadState>(AdLoadState.Idle)
    val adState: StateFlow<AdLoadState> = _adState.asStateFlow()

    private val _sdkInitialized = MutableStateFlow(false)
    val sdkInitialized: StateFlow<Boolean> = _sdkInitialized.asStateFlow()

    @Volatile
    private var cachedRewardedAd: StartAppAd? = null

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
        return STARTAPP_APP_ID.isNotBlank() && !STARTAPP_APP_ID.contains("SUBSTITUIR")
    }

    fun updateActivity(activity: Activity) {
        activityRef = WeakReference(activity)
    }

    fun isAdReady(): Boolean {
        val ad = cachedRewardedAd
        return ad != null && ad.isReady
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
            Log.i(TAG, "[Cache] Cache de anúncios Start.io invalidado.")
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
                val msg = "Start.io App ID pendente ou inválido."
                lastErrorDetail = msg
                _adState.value = AdLoadState.Error(msg)
                Log.w(TAG, "[Init] $msg")
                return@runOnMainThread
            }

            try {
                Log.i(TAG, "[Init] Inicializando Start.io SDK em modo de produção (App ID: $STARTAPP_APP_ID)...")
                StartAppSDK.init(appContext, STARTAPP_APP_ID, false)
                val consent = AdConsentManager.syncState(appContext)
                if (consent.hasUserChoice) {
                    StartAppSDK.setUserConsent(
                        appContext,
                        "pas",
                        if (consent.consentTimestampMs > 0) consent.consentTimestampMs else System.currentTimeMillis(),
                        consent.personalizedAdsGranted
                    )
                }
                _sdkInitialized.value = true
                Log.i(TAG, "[Init] Start.io SDK inicializado com sucesso.")
                val callbacks = pendingInitCallbacks.toList()
                pendingInitCallbacks.clear()
                callbacks.forEach { it.invoke() }
            } catch (e: Exception) {
                isSdkInitStarted.set(false)
                _sdkInitialized.value = false
                val msg = "Erro ao inicializar Start.io SDK: ${e.javaClass.simpleName}: ${e.message}"
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
                val err = "Start.io App ID não configurado."
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
                Log.i(TAG, "[Load] Anúncio Start.io Recompensado já pronto em cache.")
                onLoaded?.invoke()
                return@runOnMainThread
            }

            if (onLoaded != null || onFailed != null) {
                activeLoadCallbacks = Pair(onLoaded, onFailed)
            }

            if (!isLoading.compareAndSet(false, true)) {
                Log.d(TAG, "[Load] Carregamento Start.io já em andamento.")
                return@runOnMainThread
            }

            lastAttemptTimestampMs = System.currentTimeMillis()
            cachedRewardedAd = null
            lastErrorDetail = null
            _adState.value = AdLoadState.Loading("Start.io Recompensado")

            try {
                val ad = StartAppAd(appContext)
                Log.i(TAG, "[Load] Solicitando StartAppAd (REWARDED_VIDEO)...")

                ad.loadAd(StartAppAd.AdMode.REWARDED_VIDEO, object : AdEventListener {
                    override fun onReceiveAd(adInstance: Ad) {
                        runOnMainThread {
                            cancelPendingWaitTimeout()
                            cachedRewardedAd = ad
                            lastErrorDetail = null
                            isLoading.set(false)
                            _adState.value = AdLoadState.Ready("Start.io Recompensado Pronto")
                            Log.i(TAG, "[Load] StartAppAd carregado com sucesso.")
                            val cb = activeLoadCallbacks
                            activeLoadCallbacks = null
                            cb?.first?.invoke()
                        }
                    }

                    override fun onFailedToReceiveAd(adInstance: Ad?) {
                        runOnMainThread {
                            cancelPendingWaitTimeout()
                            val reason = adInstance?.errorMessage ?: "Falha ao carregar anúncio Start.io"
                            cachedRewardedAd = null
                            lastErrorDetail = reason
                            isLoading.set(false)
                            _adState.value = AdLoadState.Error(reason)
                            Log.e(TAG, "[Load] StartAppAd falhou de imediato: $reason")
                            val cb = activeLoadCallbacks
                            activeLoadCallbacks = null
                            cb?.second?.invoke(reason)
                        }
                    }
                })
            } catch (e: Exception) {
                runOnMainThread {
                    cancelPendingWaitTimeout()
                    val reason = "${e.javaClass.simpleName}: ${e.message}"
                    cachedRewardedAd = null
                    lastErrorDetail = reason
                    isLoading.set(false)
                    _adState.value = AdLoadState.Error(reason)
                    Log.e(TAG, "[Load] Exceção ao solicitar anúncio Start.io: $reason", e)
                    val cb = activeLoadCallbacks
                    activeLoadCallbacks = null
                    cb?.second?.invoke(reason)
                }
            }
        }
    }

    /**
     * Estratégia de fallback imediato e timeout de 3 segundos:
     * - Se o anúncio estiver pronto em cache, exibe-o.
     * - Se demorar mais de 3 segundos a carregar ou falhar de imediato, faz fallback automático
     *   sem bloquear o utilizador, iniciando a ligação VPN diretamente.
     */
    fun showRequiredAdOrBlock(
        activity: Activity,
        onAdClosedConnectVpn: () -> Unit,
        onBlocked: (String) -> Unit
    ) {
        activityRef = WeakReference(activity)
        runOnMainThread {
            val rewarded = cachedRewardedAd
            if (rewarded != null && rewarded.isReady) {
                displayMandatoryRewardedAd(activity, rewarded, onAdClosedConnectVpn, onBlocked)
                return@runOnMainThread
            }

            Log.i(TAG, "[Connect] Sem anúncio em cache; a carregar com timeout de 3s...")
            val fallbackTriggered = AtomicBoolean(false)

            val triggerFallback = { cleanMsg: String ->
                if (fallbackTriggered.compareAndSet(false, true)) {
                    cancelPendingWaitTimeout()
                    activeLoadCallbacks = null
                    Log.w(TAG, "[Connect] Fallback ativado ($cleanMsg). A ligar VPN sem anúncio.")
                    Toast.makeText(activity.applicationContext, cleanMsg, Toast.LENGTH_SHORT).show()
                    onAdClosedConnectVpn()
                }
            }

            scheduleWaitTimeout {
                if (activity.isFinishing || activity.isDestroyed) return@scheduleWaitTimeout
                triggerFallback("A ligar diretamente...")
            }

            loadAd(
                context = activity,
                onLoaded = {
                    if (activity.isFinishing || activity.isDestroyed) return@loadAd
                    if (fallbackTriggered.get()) return@loadAd
                    cancelPendingWaitTimeout()
                    val readyRewarded = cachedRewardedAd
                    if (readyRewarded != null && readyRewarded.isReady) {
                        displayMandatoryRewardedAd(activity, readyRewarded, onAdClosedConnectVpn) { _ ->
                            triggerFallback("A ligar diretamente...")
                        }
                    } else {
                        triggerFallback("Ligação disponível sem anúncio no momento")
                    }
                },
                onFailed = { detail ->
                    if (activity.isFinishing || activity.isDestroyed) return@loadAd
                    Log.w(TAG, "[Connect] Falha imediata no carregamento do anúncio: $detail")
                    triggerFallback("Ligação disponível sem anúncio no momento")
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
            if (rewarded == null || !rewarded.isReady) {
                onFinished(false, "Nenhum anúncio Start.io em cache.")
                return@runOnMainThread
            }

            cachedRewardedAd = null
            _adState.value = AdLoadState.Idle
            val completed = AtomicBoolean(false)

            rewarded.setVideoListener {
                Log.i(TAG, "[Show] Vídeo premiado Start.io concluído.")
            }

            val shown = rewarded.showAd(object : AdDisplayListener {
                override fun adDisplayed(ad: Ad?) {
                    Log.i(TAG, "[Show] StartAppAd exibido na tela.")
                }

                override fun adHidden(ad: Ad?) {
                    if (completed.compareAndSet(false, true)) {
                        preloadSilently(activity, force = true)
                        onFinished(true, "Anúncio Recompensado Start.io exibido com sucesso.")
                    }
                }

                override fun adClicked(ad: Ad?) {
                    Log.i(TAG, "[Show] StartAppAd clicado.")
                }

                override fun adNotDisplayed(ad: Ad?) {
                    if (completed.compareAndSet(false, true)) {
                        preloadSilently(activity, force = true)
                        onFinished(false, "Ligação disponível sem anúncio no momento.")
                    }
                }
            })

            if (!shown) {
                if (completed.compareAndSet(false, true)) {
                    onFinished(false, "Ligação disponível sem anúncio no momento.")
                }
            }
        }
    }

    private fun displayMandatoryRewardedAd(
        activity: Activity,
        rewarded: StartAppAd,
        onAdClosedConnectVpn: () -> Unit,
        onFallback: (String) -> Unit
    ) {
        cachedRewardedAd = null
        _adState.value = AdLoadState.Idle
        val callbackDelivered = AtomicBoolean(false)

        rewarded.setVideoListener {
            Log.i(TAG, "[Show] Vídeo premiado Start.io concluído com sucesso.")
        }

        val shown = rewarded.showAd(object : AdDisplayListener {
            override fun adDisplayed(ad: Ad?) {
                Log.i(TAG, "[Show] Anúncio Recompensado Start.io exibido na tela.")
            }

            override fun adHidden(ad: Ad?) {
                if (!callbackDelivered.compareAndSet(false, true)) return
                Log.i(TAG, "[Show] Anúncio fechado pelo usuário. Iniciando túnel VPN com sucesso.")
                onAdClosedConnectVpn()
                preloadSilently(activity, force = true)
            }

            override fun adClicked(ad: Ad?) {
                Log.i(TAG, "[Show] Anúncio Start.io clicado.")
            }

            override fun adNotDisplayed(ad: Ad?) {
                if (!callbackDelivered.compareAndSet(false, true)) return
                Log.e(TAG, "[Show] Falha ao renderizar anúncio Start.io Recompensado.")
                preloadSilently(activity, force = true)
                onFallback("Ligação disponível sem anúncio no momento")
            }
        })

        if (!shown) {
            if (!callbackDelivered.compareAndSet(false, true)) return
            Log.e(TAG, "[Show] Não foi possível abrir o anúncio Start.io.")
            preloadSilently(activity, force = true)
            onFallback("Ligação disponível sem anúncio no momento")
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
