package com.example.ads

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
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

object RewardedAdManager {
    private const val TAG = "StartAppDebug"

    // =========================================================================
    // START.IO — PRODUÇÃO ESTRITA (APP ID: 210992352)
    // EXIBIÇÃO OBRIGATÓRIA ANTES DE CONECTAR A VPN (SEM FALLBACK DIRETO)
    // =========================================================================
    const val STARTIO_APP_ID = "210992352"
    private const val PRELOAD_RETRY_COOLDOWN_MS = 15_000L
    private const val IN_FLIGHT_WAIT_TIMEOUT_MS = 4_500L

    private val _adState = MutableStateFlow<AdLoadState>(AdLoadState.Idle)
    val adState: StateFlow<AdLoadState> = _adState.asStateFlow()

    private val _sdkInitialized = MutableStateFlow(false)
    val sdkInitialized: StateFlow<Boolean> = _sdkInitialized.asStateFlow()

    @Volatile
    private var cachedAd: StartAppAd? = null

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

    fun isConfigured(): Boolean = STARTIO_APP_ID.isNotBlank()

    fun updateActivity(activity: Activity) {
        activityRef = WeakReference(activity)
    }

    fun isAdReady(): Boolean {
        val ad = cachedAd ?: return false
        return try {
            ad.isReady
        } catch (_: Exception) {
            _adState.value is AdLoadState.Ready
        }
    }

    /**
     * Descarta o anúncio em cache quando o utilizador altera as preferências de consentimento.
     */
    fun invalidateCachedAd() {
        runOnMainThread {
            cachedAd = null
            lastErrorDetail = null
            lastAttemptTimestampMs = 0L
            cancelPendingWaitTimeout()
            activeLoadCallbacks = null
            if (!isLoading.get()) {
                _adState.value = AdLoadState.Idle
            }
            Log.i(TAG, "[Cache] Cache de anúncio invalidado após atualização de consentimento.")
        }
    }

    /**
     * Inicializa o SDK Start.io imediatamente no arranque (Application/MainActivity).
     * Garante que initParams(appId) seja registrado antes de qualquer chamada a setUserConsent().
     */
    fun initializeSdk(context: Context, onReady: (() -> Unit)? = null) {
        if (context is Activity) {
            activityRef = WeakReference(context)
        }
        val appContext = context.applicationContext

        runOnMainThread {
            if (!isConfigured()) {
                val err = "STARTIO_APP_ID_MISSING"
                lastErrorDetail = err
                _adState.value = AdLoadState.Error(err)
                Log.e(TAG, "[Init] App ID não configurado.")
                return@runOnMainThread
            }

            if (_sdkInitialized.value) {
                if (AdConsentManager.hasUserMadeChoice(appContext)) {
                    AdConsentManager.applyToStartIoSdk(appContext)
                }
                onReady?.invoke()
                return@runOnMainThread
            }

            if (onReady != null) {
                pendingInitCallbacks.add(onReady)
            }

            if (!isSdkInitStarted.compareAndSet(false, true)) {
                return@runOnMainThread
            }

            try {
                Log.i(TAG, "[Init] Inicializando Start.io SDK (App ID: $STARTIO_APP_ID | Pacote: ${appContext.packageName})...")
                StartAppSDK.setTestAdsEnabled(false)
                StartAppSDK.enableReturnAds(false)
                StartAppSDK.enableConsent(appContext, false)
                StartAppAd.disableSplash()
                StartAppAd.disableAutoInterstitial()

                StartAppSDK.initParams(appContext, STARTIO_APP_ID)
                    .setCallback {
                        runOnMainThread {
                            _sdkInitialized.value = true
                            if (AdConsentManager.hasUserMadeChoice(appContext)) {
                                AdConsentManager.applyToStartIoSdk(appContext)
                            }
                            Log.i(TAG, "[Init] Start.io SDK inicializado com sucesso (App ID: $STARTIO_APP_ID | Pacote: ${appContext.packageName}).")
                            val callbacks = pendingInitCallbacks.toList()
                            pendingInitCallbacks.clear()
                            callbacks.forEach { it.invoke() }
                        }
                    }
                    .init()
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

    /**
     * Pré-carrega um anúncio silenciosamente após consentimento, no onResume ou após exibição.
     */
    fun preloadSilently(context: Context, force: Boolean = false) {
        if (context is Activity) {
            activityRef = WeakReference(context)
        }
        val appContext = context.applicationContext
        runOnMainThread {
            if (!isConfigured() || isAdReady() || isLoading.get()) {
                return@runOnMainThread
            }
            if (!AdConsentManager.hasUserMadeChoice(appContext)) {
                return@runOnMainThread
            }
            val now = System.currentTimeMillis()
            if (!force && lastErrorDetail != null && (now - lastAttemptTimestampMs) < PRELOAD_RETRY_COOLDOWN_MS) {
                return@runOnMainThread
            }
            loadAd(context = appContext, onLoaded = null, onFailed = null)
        }
    }

    /**
     * Carrega o anúncio (StartAppAd.loadAd()) em cache para uso obrigatório no clique de conexão.
     */
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
                val err = "STARTIO_APP_ID_MISSING"
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
                Log.i(TAG, "[Load] Anúncio já carregado e pronto em cache.")
                onLoaded?.invoke()
                return@runOnMainThread
            }

            if (onLoaded != null || onFailed != null) {
                activeLoadCallbacks = Pair(onLoaded, onFailed)
            }

            if (!isLoading.compareAndSet(false, true)) {
                Log.d(TAG, "[Load] Carregamento de anúncio já em andamento; acoplando callback.")
                return@runOnMainThread
            }

            lastAttemptTimestampMs = System.currentTimeMillis()
            cachedAd = null
            lastErrorDetail = null
            _adState.value = AdLoadState.Loading("AUTOMATIC")

            try {
                if (AdConsentManager.hasUserMadeChoice(appContext)) {
                    AdConsentManager.applyToStartIoSdk(appContext)
                }
                val adContext = resolveAdContext(appContext)
                val startAppAd = StartAppAd(adContext)
                Log.i(
                    TAG,
                    "[Load] Carregando anúncio Start.io (context=${adContext::class.java.simpleName}, " +
                        "appId=$STARTIO_APP_ID, pkg=${appContext.packageName})..."
                )

                startAppAd.loadAd(object : AdEventListener {
                    override fun onReceiveAd(ad: Ad) {
                        runOnMainThread {
                            cancelPendingWaitTimeout()
                            cachedAd = startAppAd
                            lastErrorDetail = null
                            isLoading.set(false)
                            _adState.value = AdLoadState.Ready("Start.io Pronto")
                            Log.i(TAG, "[Load] onReceiveAd: anúncio pronto para exibição obrigatória.")
                            val cb = activeLoadCallbacks
                            activeLoadCallbacks = null
                            cb?.first?.invoke()
                        }
                    }

                    override fun onFailedToReceiveAd(ad: Ad?) {
                        runOnMainThread {
                            cancelPendingWaitTimeout()
                            val rawMessage = ad?.errorMessage?.takeIf { it.isNotBlank() } ?: "NO FILL"
                            val reason = if (rawMessage.contains("status=404", ignoreCase = true) ||
                                rawMessage.contains("404", ignoreCase = true)
                            ) {
                                "$rawMessage [Verifique no portal.start.io se App ID $STARTIO_APP_ID pertence ao pacote ${appContext.packageName}]"
                            } else {
                                rawMessage
                            }
                            cachedAd = null
                            lastErrorDetail = reason
                            isLoading.set(false)
                            _adState.value = AdLoadState.Error(reason)
                            Log.e(
                                TAG,
                                "[Load] Erro ao carregar anúncio (appId=$STARTIO_APP_ID, pkg=${appContext.packageName}): $rawMessage"
                            )
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
                    cachedAd = null
                    lastErrorDetail = reason
                    isLoading.set(false)
                    _adState.value = AdLoadState.Error(reason)
                    Log.e(TAG, "[Load] Exceção ao carregar anúncio: $reason", e)
                    val cb = activeLoadCallbacks
                    activeLoadCallbacks = null
                    cb?.second?.invoke(reason)
                }
            }
        }
    }

    /**
     * Fluxo obrigatório do botão "CONECTAR-SE":
     * - Se `startAppAd.isReady()`: exibe imediatamente e só chama `onAdClosedConnectVpn()` em `adHidden`.
     * - Se já houver carregamento em curso (ou ao iniciar novo pedido), aguarda até ~4,5s pelo
     *   término do pedido atual antes de abortar; se falhar ou estourar o tempo, BLOQUEIA a VPN.
     */
    fun showRequiredAdOrBlock(
        activity: Activity,
        onAdClosedConnectVpn: () -> Unit,
        onBlocked: (String) -> Unit
    ) {
        activityRef = WeakReference(activity)
        runOnMainThread {
            val ad = cachedAd
            val ready = try {
                ad != null && ad.isReady
            } catch (_: Exception) {
                false
            }

            if (ad != null && ready) {
                displayMandatoryAd(activity, ad, onAdClosedConnectVpn, onBlocked)
                return@runOnMainThread
            }

            val wasAlreadyLoading = isLoading.get()
            Log.i(
                TAG,
                if (wasAlreadyLoading) {
                    "[Connect] Carregamento em curso; aguardando resposta do anúncio (máx ${IN_FLIGHT_WAIT_TIMEOUT_MS}ms)..."
                } else {
                    "[Connect] Sem anúncio em cache; solicitando carregamento obrigatório antes de conectar..."
                }
            )

            scheduleWaitTimeout {
                if (activity.isFinishing || activity.isDestroyed) return@scheduleWaitTimeout
                activeLoadCallbacks = null
                Log.w(TAG, "Tempo limite ao aguardar anúncio. A conexão VPN está bloqueada.")
                onBlocked("Anúncio indisponível no momento. Aguarde um momento e tente novamente.")
            }

            loadAd(
                context = activity,
                onLoaded = {
                    cancelPendingWaitTimeout()
                    if (activity.isFinishing || activity.isDestroyed) return@loadAd
                    val readyAd = cachedAd
                    if (readyAd != null && isAdReady()) {
                        displayMandatoryAd(activity, readyAd, onAdClosedConnectVpn, onBlocked)
                    } else {
                        Log.w(TAG, "Anúncio indisponível após carregamento. A conexão VPN está bloqueada.")
                        onBlocked("Anúncio não ficou pronto para exibição. Tente novamente.")
                    }
                },
                onFailed = { detail ->
                    cancelPendingWaitTimeout()
                    if (activity.isFinishing || activity.isDestroyed) return@loadAd
                    Log.w(TAG, "Anúncio indisponível ($detail). A conexão VPN está bloqueada.")
                    onBlocked("Anúncio indisponível no momento ($detail). Aguarde um momento e tente novamente.")
                }
            )
        }
    }

    /**
     * Exibição isolada para o botão de diagnóstico nas Definições.
     */
    fun showLoadedAd(
        activity: Activity,
        onFinished: (Boolean, String) -> Unit
    ) {
        activityRef = WeakReference(activity)
        runOnMainThread {
            val ad = cachedAd
            if (ad == null || !isAdReady()) {
                onFinished(false, "Nenhum anúncio em cache. Use '1. Carregar' primeiro.")
                return@runOnMainThread
            }

            cachedAd = null
            _adState.value = AdLoadState.Idle
            val completed = AtomicBoolean(false)

            try {
                val shown = ad.showAd(object : AdDisplayListener {
                    override fun adHidden(hiddenAd: Ad) {
                        if (completed.compareAndSet(false, true)) {
                            preloadSilently(activity, force = true)
                            onFinished(true, "Anúncio exibido e fechado com sucesso.")
                        }
                    }

                    override fun adDisplayed(displayedAd: Ad) {
                        Log.i(TAG, "[Show] Anúncio visível.")
                    }

                    override fun adClicked(clickedAd: Ad) {
                        Log.d(TAG, "[Show] Anúncio clicado.")
                    }

                    override fun adNotDisplayed(notDisplayedAd: Ad) {
                        if (completed.compareAndSet(false, true)) {
                            val reason = notDisplayedAd.errorMessage ?: "Falha ao exibir o anúncio."
                            preloadSilently(activity, force = true)
                            onFinished(false, reason)
                        }
                    }
                })

                if (!shown && completed.compareAndSet(false, true)) {
                    preloadSilently(activity, force = true)
                    onFinished(false, "Não foi possível abrir o anúncio.")
                }
            } catch (e: Exception) {
                if (completed.compareAndSet(false, true)) {
                    onFinished(false, "Erro ao exibir: ${e.message}")
                }
            }
        }
    }

    private fun displayMandatoryAd(
        activity: Activity,
        ad: StartAppAd,
        onAdClosedConnectVpn: () -> Unit,
        onBlocked: (String) -> Unit
    ) {
        cachedAd = null
        _adState.value = AdLoadState.Idle
        val callbackDelivered = AtomicBoolean(false)

        try {
            val shown = ad.showAd(object : AdDisplayListener {
                override fun adHidden(hiddenAd: Ad) {
                    if (!callbackDelivered.compareAndSet(false, true)) return
                    Log.i(TAG, "[Show] adHidden — usuário fechou o anúncio. Iniciando conexão VPN.")
                    onAdClosedConnectVpn()
                    preloadSilently(activity, force = true)
                }

                override fun adDisplayed(displayedAd: Ad) {
                    Log.i(TAG, "[Show] adDisplayed — anúncio visível na tela.")
                }

                override fun adClicked(clickedAd: Ad) {
                    Log.d(TAG, "[Show] adClicked — anúncio clicado.")
                }

                override fun adNotDisplayed(notDisplayedAd: Ad) {
                    if (!callbackDelivered.compareAndSet(false, true)) return
                    val reason = notDisplayedAd.errorMessage?.takeIf { it.isNotBlank() } ?: "Falha ao renderizar"
                    Log.e(TAG, "Falha ao exibir o anúncio ($reason). A conexão foi abortada por segurança.")
                    lastErrorDetail = reason
                    _adState.value = AdLoadState.Error(reason)
                    preloadSilently(activity, force = true)
                    onBlocked("Falha ao exibir o anúncio. Por favor, tente novamente.")
                }
            })

            if (!shown && callbackDelivered.compareAndSet(false, true)) {
                Log.e(TAG, "Falha ao exibir o anúncio (showAd=false). A conexão foi abortada por segurança.")
                preloadSilently(activity, force = true)
                onBlocked("Não foi possível exibir o anúncio. Por favor, tente novamente.")
            }
        } catch (e: Exception) {
            if (callbackDelivered.compareAndSet(false, true)) {
                Log.e(TAG, "Exceção ao exibir o anúncio (${e.message}). Conexão abortada.", e)
                preloadSilently(activity, force = true)
                onBlocked("Erro ao exibir o anúncio. Tente novamente.")
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

    private fun resolveAdContext(appContext: Context): Context {
        val act = activityRef?.get()
        return if (act != null && !act.isFinishing && !act.isDestroyed) act else appContext
    }

    private fun runOnMainThread(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }
}
