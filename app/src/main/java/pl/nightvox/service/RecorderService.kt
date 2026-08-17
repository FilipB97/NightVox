package pl.nightvox.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.StatFs
import android.util.Log
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import pl.nightvox.NightVoxApp
import pl.nightvox.audio.AudioCapture
import pl.nightvox.audio.ClipStats
import pl.nightvox.audio.DiscardReason
import pl.nightvox.audio.Frame
import pl.nightvox.audio.Gate
import pl.nightvox.audio.GateAction
import pl.nightvox.audio.GateConfig
import pl.nightvox.audio.GateState
import pl.nightvox.audio.NoiseFloorTracker
import pl.nightvox.audio.RingBuffer
import pl.nightvox.audio.vad.SileroVad
import pl.nightvox.audio.vad.VadResult
import pl.nightvox.data.ClipRepository
import pl.nightvox.data.NightVoxSettings
import pl.nightvox.encode.ClipWriter
import pl.nightvox.encode.FinishedClip
import pl.nightvox.encode.SileroSpeechDetector
import pl.nightvox.encode.WavDumpWriter
import pl.nightvox.util.DiagnosticsLog
import java.io.File
import java.util.Calendar
import java.util.concurrent.atomic.AtomicInteger

/**
 * Foreground service typu `microphone` — właściciel pipeline'u audio na całą noc.
 *
 * Startuje **wyłącznie z widocznego Activity**: od Androida 14 FGS mikrofonowy nie może
 * wystartować z tła, więc nie ma tu żadnego receivera ani alarmu, który by go budził.
 *
 * Przepływ: `AudioCapture` (wątek urgent-audio) → [pipeline] (kanał + coroutine) →
 * [Gate] → [ClipWriter] (osobny wątek enkodera).
 */
class RecorderService : Service() {

    private val container by lazy { (application as NightVoxApp).container }
    private val notifications by lazy { NotificationHelper(this) }
    private val diagnostics: DiagnosticsLog by lazy { container.diagnostics }
    private val repository: ClipRepository by lazy { container.clipRepository }

    private var scope: CoroutineScope? = null
    private var startJob: Job? = null
    private var pipelineJob: Job? = null
    private var monitorJob: Job? = null
    private var notificationJob: Job? = null

    private var capture: AudioCapture? = null
    private var gate: Gate? = null
    private var clipWriter: ClipWriter? = null
    private var wavDump: WavDumpWriter? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var events: Channel<PipelineEvent>? = null
    private val droppedFrames = AtomicInteger(0)

    private var sessionId: String? = null
    private var settings: NightVoxSettings = NightVoxSettings.DEFAULTS
    private var sessionStartedAt = 0L
    private var interruptions = 0
    private var clipCount = 0
    private var discardedCount = 0
    private var vadActive = false
    private var stopping = false

    private sealed interface PipelineEvent {
        @JvmInline
        value class Audio(val frame: Frame) : PipelineEvent
        data object CaptureRestart : PipelineEvent
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifications.ensureChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService() zobowiązuje do wywołania startForeground() — niezależnie
        // od tego, co zrobimy dalej. Wcześniej dwie ścieżki (ponowny START przy trwającej
        // sesji, intent bez akcji) wracały bez tego, a system odpowiada na złamanie tej
        // obietnicy ubiciem procesu.
        if (!ensureForeground()) return START_NOT_STICKY

        when (intent?.action) {
            ACTION_STOP -> {
                stopSession(ClipRepository.END_REASON_USER)
                return START_NOT_STICKY
            }
            ACTION_START -> startSession()
            else -> if (sessionId == null) {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        // NOT_STICKY: system nie ma prawa nas wskrzesić w tle — FGS mikrofonowy i tak
        // nie wystartuje bez widocznego Activity, a „zombie” serwis tylko myli.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopSession(ClipRepository.END_REASON_ERROR, fromDestroy = true)
        super.onDestroy()
    }

    // --- start / stop sesji ---

    /**
     * Wchodzi na pierwszy plan. Idempotentne — kolejne wywołania tylko odświeżają notyfikację.
     * Zwraca `false`, gdy system odmówił (najczęściej `ForegroundServiceStartNotAllowedException`,
     * bo żadne Activity nie było widoczne).
     */
    private fun ensureForeground(): Boolean = try {
        ServiceCompat.startForeground(
            this,
            NotificationHelper.NOTIFICATION_ID,
            notifications.buildRecordingNotification(RecorderStateHolder.state.value),
            foregroundServiceType(),
        )
        true
    } catch (e: Exception) {
        Log.e(TAG, "startForeground odrzucone", e)
        diagnostics.log("service", "startForeground odrzucone: ${e.javaClass.name}: ${e.message}")
        RecorderStateHolder.update {
            RecorderState(lastError = "System nie pozwolił wystartować nagrywania z tła. Otwórz apkę i spróbuj ponownie.")
        }
        stopSelf()
        false
    }

    private fun startSession() {
        if (sessionId != null || scope != null) return
        stopping = false

        RecorderStateHolder.update { RecorderState(isRunning = true, startedAtMs = System.currentTimeMillis()) }
        notifications.updateRecordingNotification(RecorderStateHolder.state.value)

        // Bez tego dowolny wyjątek w korutynie sesji leciał do domyślnego handlera i ubijał
        // proces — użytkownik widział znikającą apkę zamiast informacji, co się zepsuło.
        val errors = CoroutineExceptionHandler { _, error ->
            Log.e(TAG, "Sesja przewróciła się", error)
            diagnostics.log("service", "wyjątek w sesji: ${error.javaClass.name}: ${error.message}")
            RecorderStateHolder.update {
                it.copy(
                    isRunning = false,
                    lastError = "Nagrywanie przerwane błędem: ${error.javaClass.simpleName}. " +
                        "Szczegóły w Ustawienia → Diagnostyka → Udostępnij log.",
                )
            }
            notifications.postAlert(
                "NightVox przerwał nagrywanie",
                "Wystąpił błąd: ${error.javaClass.simpleName}. Log diagnostyczny zawiera szczegóły.",
            )
            runCatching { stopSession(ClipRepository.END_REASON_ERROR, fromDestroy = true) }
        }
        val serviceScope = CoroutineScope(SupervisorJob() + container.ioDispatcher + errors)
        scope = serviceScope

        startJob = serviceScope.launch {
            settings = container.settingsStore.current()
            diagnostics.enabled = settings.diagnosticsEnabled

            val config = settings.toGateConfig()
            sessionStartedAt = System.currentTimeMillis()
            interruptions = 0
            clipCount = 0
            discardedCount = 0
            droppedFrames.set(0)

            val id = repository.startSession(settings, noiseFloorDb = 0f)
            sessionId = id
            diagnostics.log("session", "start id=$id config=$config vad=${settings.vadEnabled}")
            RecorderStateHolder.update { it.copy(sessionId = id, startedAtMs = sessionStartedAt) }

            acquireWakeLock()
            setupPipeline(config, serviceScope)
            startMonitors(serviceScope)
        }
    }

    private fun setupPipeline(config: GateConfig, serviceScope: CoroutineScope) {
        val channel = Channel<PipelineEvent>(capacity = EVENT_CHANNEL_CAPACITY)
        events = channel

        val newGate = Gate(
            config = config,
            floorTracker = NoiseFloorTracker(config.warmupFrames),
            ringBuffer = RingBuffer(maxOf(config.preRollSamples, config.frameSamples)),
        )
        gate = newGate

        val detector = if (settings.vadEnabled) {
            SileroVad.create(this, config.sampleRate)?.let { vad ->
                SileroSpeechDetector(vad, config.sampleRate, settings.vadThreshold)
            }.also {
                if (it == null) diagnostics.log("vad", "model niedostępny — zostaje sama bramka RMS")
            }
        } else {
            null
        }
        vadActive = detector != null

        val writer = ClipWriter(
            clipsDir = container.clipsDir,
            sampleRate = config.sampleRate,
            keepDiscarded = settings.keepDiscardedClips,
            speechDetector = detector,
            callbacks = writerCallbacks(),
        )
        clipWriter = writer
        writer.start(serviceScope)

        if (settings.debugWavDump) {
            wavDump = WavDumpWriter(
                File(container.debugDir, "session-${sessionStartedAt}.wav"),
                config.sampleRate,
            ).also { runCatching { it.open() } }
        }

        pipelineJob = serviceScope.launch { runPipeline(channel, newGate, writer, config) }

        val audioCapture = AudioCapture(
            context = this,
            sampleRate = config.sampleRate,
            frameSamples = config.frameSamples,
            listener = captureListener(channel),
        )
        capture = audioCapture
        audioCapture.start()
    }

    private suspend fun runPipeline(
        channel: Channel<PipelineEvent>,
        gate: Gate,
        writer: ClipWriter,
        config: GateConfig,
    ) {
        var framesSinceUiUpdate = 0
        val uiUpdateEvery = maxOf(1, (UI_UPDATE_MS / config.frameMs).toInt())

        for (event in channel) {
            when (event) {
                is PipelineEvent.CaptureRestart -> {
                    gate.resetAfterCaptureRestart().forEach { writer.submit(it) }
                    diagnostics.log("gate", "reset po restarcie capture")
                }

                is PipelineEvent.Audio -> {
                    val frame = event.frame
                    wavDump?.write(frame.samples)

                    val previousState = gate.state
                    val actions = gate.process(frame)
                    for (action in actions) writer.submit(action)
                    if (gate.state != previousState) {
                        diagnostics.log(
                            "gate",
                            "${previousState.name} → ${gate.state.name} " +
                                "level=${"%.1f".format(gate.lastLevelDb)} " +
                                "floor=${"%.1f".format(gate.floorDb)} " +
                                "trig=${"%.1f".format(gate.triggerThresholdDb)}",
                        )
                    }

                    if (++framesSinceUiUpdate >= uiUpdateEvery) {
                        framesSinceUiUpdate = 0
                        publishLevel(gate)
                    }
                }
            }
        }
    }

    private fun publishLevel(gate: Gate) {
        RecorderStateHolder.update { current ->
            current.copy(
                gateState = gate.state,
                levelDb = gate.lastLevelDb,
                floorDb = gate.floorDb,
                thresholdDb = gate.triggerThresholdDb,
                warmupRemainingMs = if (gate.isWarmingUp) gate.warmupRemainingMs else 0L,
                levelUpdates = current.levelUpdates + 1,
            )
        }
    }

    private fun captureListener(channel: Channel<PipelineEvent>) = object : AudioCapture.Listener {
        override fun onFrame(frame: Frame) {
            // Wołane z wątku urgent-audio: tylko wrzucenie do kanału, zero pracy.
            val result = channel.trySend(PipelineEvent.Audio(frame))
            if (result.isFailure && droppedFrames.incrementAndGet() % DROP_LOG_EVERY == 1) {
                diagnostics.log("pipeline", "kanał pełny, zgubiono ${droppedFrames.get()} ramek")
            }
        }

        override fun onCaptureError(reason: String, attempt: Int, delayMs: Long) {
            interruptions++
            diagnostics.log("capture", "błąd: $reason (próba $attempt, restart za ${delayMs}ms)")
            RecorderStateHolder.update {
                it.copy(interruptions = interruptions, lastError = reason)
            }
            scope?.launch { sessionId?.let { repository.updateSessionStats(it, lastFloorDb(), interruptions) } }
        }

        override fun onCaptureRestarted(source: Int) {
            diagnostics.log("capture", "wznowione na ${AudioCapture.sourceName(source)}")
            channel.trySend(PipelineEvent.CaptureRestart)
            RecorderStateHolder.update {
                it.copy(lastError = null, audioSource = AudioCapture.sourceName(source))
            }
        }

        override fun onSilencedChanged(silenced: Boolean) {
            diagnostics.log("capture", if (silenced) "system wyciszył strumień" else "wyciszenie ustąpiło")
            RecorderStateHolder.update { it.copy(isSilenced = silenced) }
            notifications.updateRecordingNotification(RecorderStateHolder.state.value)
        }

        override fun onFatalError(reason: String) {
            diagnostics.log("capture", "awaria krytyczna: $reason")
            notifications.postAlert("NightVox przerwał nagrywanie", reason)
            stopSession(ClipRepository.END_REASON_ERROR)
        }
    }

    private fun writerCallbacks() = object : ClipWriter.Callbacks {
        override suspend fun onClipFinished(clip: FinishedClip) {
            val id = sessionId ?: return
            val vad = clip.vad
            // VAD nie kasuje niczego: klip poniżej progu ląduje w koszu, skąd da się go
            // odsłuchać i przywrócić. Silero potrafi wziąć chrapanie za mowę i przegapić
            // ciche mamrotanie, więc twardy filtr byłby tu nieuczciwy wobec danych.
            val belowThreshold = vad != null && vad.maxProbability < settings.vadThreshold

            repository.addClip(
                sessionId = id,
                file = clip.file,
                stats = clip.stats,
                discardReason = if (belowThreshold) ClipRepository.DISCARD_REASON_LOW_VAD else null,
                vadScore = vad?.maxProbability,
            )
            if (belowThreshold) discardedCount++ else clipCount++

            diagnostics.log(
                "clip",
                "zapisany ${clip.file.name} ${clip.stats.durationMs}ms " +
                    "voiced=${clip.stats.voicedMs}ms peak=${"%.1f".format(clip.stats.peakDb)} " +
                    "segments=${clip.stats.segments}" + vadSuffix(vad) +
                    if (belowThreshold) " -> kosz (poniżej progu VAD)" else "",
            )
            RecorderStateHolder.update { it.copy(clipCount = clipCount, discardedCount = discardedCount) }
            notifications.updateRecordingNotification(RecorderStateHolder.state.value)
        }

        override suspend fun onClipDiscarded(
            reason: DiscardReason,
            stats: ClipStats,
            file: File?,
            vad: VadResult?,
        ) {
            discardedCount++
            val sessionId = sessionId
            if (file != null && sessionId != null) {
                repository.addClip(
                    sessionId = sessionId,
                    file = file,
                    stats = stats,
                    discardReason = reason.name,
                    vadScore = vad?.maxProbability,
                )
            }
            diagnostics.log(
                "clip",
                "odrzucony (${reason.name}) voiced=${stats.voicedMs}ms " +
                    "peak=${"%.1f".format(stats.peakDb)}${vadSuffix(vad)} " +
                    if (file != null) "zachowany" else "skasowany",
            )
            RecorderStateHolder.update { it.copy(discardedCount = discardedCount) }
        }

        override suspend fun onWriterError(message: String, cause: Throwable?) {
            Log.w(TAG, message, cause)
            diagnostics.log("writer", "$message: ${cause?.message ?: "—"}")
            RecorderStateHolder.update { it.copy(lastError = message) }
        }

        override suspend fun onOutOfSpace(freeBytes: Long) {
            diagnostics.log("writer", "brak miejsca ($freeBytes B) — kończę sesję")
            notifications.postAlert(
                "NightVox zatrzymał nagrywanie",
                "Zostało mniej niż 200 MB wolnego miejsca. Zwolnij miejsce i uruchom sesję ponownie.",
            )
            stopSession(ClipRepository.END_REASON_NO_SPACE)
        }
    }

    private fun vadSuffix(vad: VadResult?): String = if (vad == null) {
        ""
    } else {
        " vad=${"%.2f".format(vad.maxProbability)} mowa=${vad.speechMs}ms"
    }

    private fun startMonitors(serviceScope: CoroutineScope) {
        notificationJob = serviceScope.launch {
            while (true) {
                delay(NOTIFICATION_REFRESH_MS)
                notifications.updateRecordingNotification(RecorderStateHolder.state.value)
            }
        }
        monitorJob = serviceScope.launch {
            while (true) {
                delay(MONITOR_INTERVAL_MS)
                val free = freeBytes()
                RecorderStateHolder.update { it.copy(freeBytes = free) }
                sessionId?.let { repository.updateSessionStats(it, lastFloorDb(), interruptions) }

                if (free in 0 until ClipWriter.MIN_FREE_BYTES) {
                    notifications.postAlert(
                        "NightVox zatrzymał nagrywanie",
                        "Zostało mniej niż 200 MB wolnego miejsca.",
                    )
                    stopSession(ClipRepository.END_REASON_NO_SPACE)
                    return@launch
                }
                if (shouldAutoStop()) {
                    diagnostics.log("session", "auto-stop")
                    notifications.postAlert(
                        "NightVox zakończył sesję",
                        "Zapisano $clipCount klipów. Odrzucono $discardedCount zdarzeń poniżej progu.",
                    )
                    stopSession(ClipRepository.END_REASON_AUTO_STOP)
                    return@launch
                }
            }
        }
    }

    private fun shouldAutoStop(): Boolean {
        val now = System.currentTimeMillis()
        val elapsedHours = (now - sessionStartedAt) / 3_600_000.0
        if (settings.maxSessionHours > 0 && elapsedHours >= settings.maxSessionHours) return true

        val hour = settings.autoStopHour ?: return false
        val calendar = Calendar.getInstance()
        val target = (calendar.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, settings.autoStopMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        // Sesja startuje wieczorem, auto-stop wypada rano — czyli po północy, następnego dnia.
        if (target.timeInMillis <= sessionStartedAt) target.add(Calendar.DAY_OF_YEAR, 1)
        return now >= target.timeInMillis
    }

    private fun stopSession(reason: String, fromDestroy: Boolean = false) {
        if (stopping) return
        stopping = true

        val runningScope = scope

        // Domykanie musi się zdarzyć nawet gdy serwis jest już zabijany — stąd runBlocking
        // w gałęzi onDestroy. Bez tego ostatni klip zostaje bez nagłówka MP4.
        val finalize: suspend () -> Unit = {
            // Stop tuż po starcie: zaczekaj, aż pipeline w ogóle powstanie, inaczej
            // rozbieramy do połowy zbudowaną sesję i zostawiamy działający AudioRecord.
            startJob?.join()
            // sessionId odczytujemy dopiero po join() — przy stopie w trakcie startu
            // jeszcze go nie było i sesja zostałaby w bazie bez `endedAt`.
            val id = sessionId
            capture?.stop()
            capture = null
            events?.close()
            events = null
            pipelineJob?.join()
            gate?.flush()?.forEach { clipWriter?.submit(it) }
            clipWriter?.close()
            wavDump?.close()
            if (id != null) {
                repository.updateSessionStats(id, lastFloorDb(), interruptions)
                repository.endSession(id, reason)
            }
            diagnostics.log(
                "session",
                "koniec ($reason) klipy=$clipCount odrzucone=$discardedCount vad=$vadActive " +
                    "przerwania=$interruptions zgubione_ramki=${droppedFrames.get()}",
            )
        }

        if (fromDestroy || runningScope == null) {
            runBlocking { runCatching { finalize() } }
            cleanup()
        } else {
            runningScope.launch {
                runCatching { finalize() }
                ServiceCompat.stopForeground(this@RecorderService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                // cleanup() kasuje własny scope tej korutyny — musi być ostatnie.
                cleanup()
            }
        }
    }

    private fun cleanup() {
        monitorJob?.cancel()
        notificationJob?.cancel()
        startJob = null
        pipelineJob = null
        monitorJob = null
        notificationJob = null
        gate = null
        clipWriter = null
        wavDump = null
        sessionId = null
        releaseWakeLock()
        scope?.cancel()
        scope = null
        RecorderStateHolder.reset()
    }

    /**
     * Typ `microphone` pojawił się dopiero w API 30 — na Androidzie 10 podanie go rozjeżdża
     * się z manifestem i `startForeground` leci wyjątkiem. Tam wystarcza zwykły FGS: ograniczenia
     * dostępu do mikrofonu z usług pierwszoplanowych weszły później.
     */
    private fun foregroundServiceType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE
        }

    private fun lastFloorDb(): Float = gate?.floorDb ?: 0f

    private fun freeBytes(): Long = runCatching {
        val stat = StatFs(container.clipsDir.absolutePath)
        stat.availableBlocksLong * stat.blockSizeLong
    }.getOrDefault(-1L)

    /**
     * Aktywny `AudioRecord` zwykle trzyma CPU sam z siebie, ale na części ROM-ów nie —
     * i wtedy budzisz się rano z pustą sesją (§6.4).
     */
    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val power = getSystemService(PowerManager::class.java)
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            runCatching { acquire(MAX_WAKE_LOCK_MS) }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
        wakeLock = null
    }

    companion object {
        private const val TAG = "NightVox/Service"
        private const val WAKE_LOCK_TAG = "NightVox::session"
        private const val MAX_WAKE_LOCK_MS = 14L * 60 * 60 * 1000
        private const val EVENT_CHANNEL_CAPACITY = 512
        private const val UI_UPDATE_MS = 100L
        private const val NOTIFICATION_REFRESH_MS = 10_000L
        private const val MONITOR_INTERVAL_MS = 30_000L
        private const val DROP_LOG_EVERY = 50

        const val ACTION_START = "pl.nightvox.action.START"
        const val ACTION_STOP = "pl.nightvox.action.STOP"

        /** Startuj tylko z widocznego Activity — od API 34 inaczej się nie da. */
        fun start(context: Context) {
            val intent = Intent(context, RecorderService::class.java).setAction(ACTION_START)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, RecorderService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
