# NightVox — plan implementacji

Aplikacja Android do nagrywania mówienia przez sen. Nagrywa **tylko zdarzenia dźwiękowe**, nie całą noc: bramkowanie progiem adaptacyjnym + bufor pre-roll + hangover. Wszystko lokalnie, bez chmury (do fazy 3 apka nie ma nawet uprawnienia INTERNET).

---

## 1. Założenia i zakres

**Cel:** rano dostaję listę 5–30 klipów po kilka–kilkanaście sekund zamiast 8 godzin ciszy.

**Twarde wymagania:**
- Działa całą noc przy zablokowanym ekranie, telefon na ładowarce.
- Nie gubi pierwszej sylaby (dlatego pre-roll — bez tego cała apka jest bezużyteczna).
- Przetrwa przerwanie mikrofonu (połączenie, inna apka, restart audio HAL) i wznowi nagrywanie.
- Zużycie miejsca: rząd 5–30 MB na noc, nie 500 MB.

**Poza zakresem (na razie:)** tracking faz snu, integracja z Health Connect, chmura, konta użytkowników.

**Stack:** Kotlin, Jetpack Compose + Material 3, Room, WorkManager, coroutines. Bez DI frameworka na start — ręczny `AppContainer` wystarczy; Hilt dopiero jeśli graf urośnie.

**SDK:** `minSdk 29`, `compileSdk`/`targetSdk` = najnowszy stabilny. Nie wpisuj konkretnych wersji bibliotek z pamięci — użyj version catalog (`libs.versions.toml`) i najnowszych stabilnych z BOM-ów.

---

## 2. Uprawnienia i konfiguracja manifestu

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
<!-- faza 3, dopiero przy Telegramie: -->
<!-- <uses-permission android:name="android.permission.INTERNET" /> -->

<service
    android:name=".service.RecorderService"
    android:foregroundServiceType="microphone"
    android:exported="false" />
```

**Krytyczne ograniczenia platformy — uwzględnij w kodzie od początku:**

1. Od Androida 14 (API 34) FGS typu `microphone` **nie może** wystartować z tła. Serwis musi być odpalony, gdy Activity jest widoczne. Nie próbuj startować go z `BroadcastReceiver` czy `AlarmManager`.
2. `POST_NOTIFICATIONS` trzeba poprosić runtime od API 33, inaczej notyfikacja FGS jest niewidoczna (ale serwis dalej żyje).
3. Optymalizacja baterii: sprawdź `PowerManager.isIgnoringBatteryOptimizations(packageName)`. Jeśli `false`, pokaż kartę w UI z `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. To jest sideload/personal use, więc restrykcje Play Store nie obowiązują — ale zostaw to jako opcjonalny prompt, nie blokadę.
4. OEM-owe zabijanie procesów (Xiaomi, Samsung, OnePlus, Huawei) — w Settings dodaj sekcję „Rozwiązywanie problemów" z krótką instrukcją i linkiem do `dontkillmyapp.com`.

---

## 3. Architektura

```
app/src/main/java/pl/nightvox/
├── audio/
│   ├── AudioCapture.kt          # AudioRecord + pętla odczytu, emituje Frame
│   ├── Frame.kt                 # data class: ShortArray, timestampMs
│   ├── RingBuffer.kt            # kołowy bufor PCM na pre-roll
│   ├── LevelMeter.kt            # RMS -> dBFS, peak
│   ├── NoiseFloorTracker.kt     # adaptacyjne tło
│   ├── Gate.kt                  # maszyna stanów: IDLE/ARMED/RECORDING/HANGOVER
│   └── vad/SileroVad.kt         # faza 3
├── encode/
│   ├── AacEncoder.kt            # MediaCodec + MediaMuxer -> .m4a
│   └── ClipWriter.kt            # koordynuje: otwiera plik, karmi enkoder, zamyka
├── service/
│   ├── RecorderService.kt       # ForegroundService, właściciel pipeline'u
│   ├── RecorderState.kt         # StateFlow konsumowany przez UI
│   └── NotificationHelper.kt
├── data/
│   ├── db/  (NightVoxDatabase, SessionDao, ClipDao, entities)
│   ├── ClipRepository.kt
│   └── SettingsStore.kt         # DataStore Preferences
├── work/
│   ├── RetentionWorker.kt       # kasowanie starych klipów
│   ├── TranscribeWorker.kt      # faza 3
│   └── TelegramWorker.kt        # faza 3
└── ui/
    ├── home/, sessions/, clip/, settings/, calibration/
```

**Przepływ danych:** `AudioCapture` (wątek dedykowany, `THREAD_PRIORITY_URGENT_AUDIO`) → `Channel<Frame>` → `Gate` → gdy nagrywa, `Channel<ShortArray>` → `AacEncoder` (osobny wątek). Nigdy nie enkoduj na wątku odczytu — `MediaCodec` potrafi zablokować się na kilkadziesiąt ms i wtedy `AudioRecord` gubi próbki.

---

## 4. Pipeline audio — szczegóły

### 4.1 Capture

```kotlin
source      = MediaRecorder.AudioSource.VOICE_RECOGNITION
sampleRate  = 16_000
channel     = AudioFormat.CHANNEL_IN_MONO
encoding    = AudioFormat.ENCODING_PCM_16BIT
frameMs     = 20                    // 320 sampli / 640 B
bufferBytes = max(minBufferSize(...) * 4, frameBytes * 16)
```

`VOICE_RECOGNITION` zamiast `MIC`, bo wyłącza część agresywnego AGC/NS producenta — mamrotanie przez sen jest ciche i AGC potrafi je „wyrównać" do poziomu tła. Jeśli na danym urządzeniu `VOICE_RECOGNITION` zwróci `STATE_UNINITIALIZED`, fallback na `UNPROCESSED`, potem na `MIC`.

Jeśli `AudioEffect` dostępne — jawnie wyłącz `NoiseSuppressor` i `AutomaticGainControl` gdy `isAvailable()`.

### 4.2 Poziom i tło szumu

Na każdą ramkę:
```
rms  = sqrt(Σ s² / n)
dbfs = 20 * log10(max(rms, 1.0) / 32768.0)     // ok. -90..0
```

`NoiseFloorTracker` — EMA aktualizowana **wyłącznie gdy bramka jest zamknięta**, żeby długa wypowiedź nie podniosła tła:

```kotlin
if (state == IDLE) {
    val alpha = if (dbfs < floor) 0.05 else 0.002   // szybki spadek, wolny wzrost
    floor = floor + alpha * (dbfs - floor)
}
```

Inicjalizacja: pierwsze 10 s sesji to warm-up — bramka nieaktywna, tylko zbieranie tła (mediana z ramek, nie EMA, żeby trzask ładowarki nie zatruł startu).

### 4.3 Bramka (`Gate`) — maszyna stanów

| Stan | Wejście | Wyjście |
|---|---|---|
| `WARMUP` | start sesji | po 10 s → `IDLE` |
| `IDLE` | — | `attackFrames` kolejnych ramek > `floor + triggerDeltaDb` → `RECORDING` |
| `RECORDING` | zrzuca pre-roll, potem ramki na bieżąco | ramka < próg → `HANGOVER`; przekroczenie `maxClipMs` → zamknij i wróć do `IDLE` |
| `HANGOVER` | dalej nagrywa | ramka > próg → z powrotem `RECORDING`; upływ `hangoverMs` → zamknij klip |

Po zamknięciu klipu: jeśli nowy trigger padnie w ciągu `mergeGapMs`, **dopisz do tego samego pliku** zamiast tworzyć nowy (inaczej jedna wypowiedź rozsypie się na 5 klipów po 2 s).

Odrzucaj klip, jeśli sumaryczny czas ramek powyżej progu < `minVoicedMs` — to filtruje trzaski, skrzypnięcie łóżka, przełączanie ładowarki.

### 4.4 Pre-roll

`RingBuffer` na `preRollMs` audio (3 s @ 16 kHz PCM16 = 96 KB — bez znaczenia dla pamięci). Zapis każdej ramki niezależnie od stanu bramki. Przy przejściu w `RECORDING` cała zawartość leci do enkodera jako pierwsza. Bufor **nie** jest czyszczony po zrzucie — po prostu leci dalej.

### 4.5 Enkoder

`MediaCodec` AAC-LC, 32 kbps mono 16 kHz → ok. 4 KB/s, czyli 60-sekundowy klip ≈ 240 KB. `MediaMuxer` w kontenerze MPEG-4 → `.m4a`.

Tryb asynchroniczny albo klasyczna pętla `dequeueInputBuffer`/`dequeueOutputBuffer` — obojętne, byle na własnym wątku. Pamiętaj o `BUFFER_FLAG_END_OF_STREAM` przy zamykaniu, inaczej ostatnie ~100 ms przepada.

Zapis do `filesDir/clips/{yyyy-MM-dd}/{HHmmss}.m4a` (app-private, bez uprawnień do storage).

---

## 5. Parametry domyślne

| Parametr | Default | Zakres w UI | Uwagi |
|---|---|---|---|
| `triggerDeltaDb` | 12 dB | 6–24 | główna „czułość" |
| `attackFrames` | 3 (60 ms) | 1–10 | anty-trzask |
| `preRollMs` | 3000 | 1000–6000 | |
| `hangoverMs` | 4000 | 1000–10000 | pauzy w mowie przez sen bywają długie |
| `mergeGapMs` | 2000 | 0–5000 | |
| `minVoicedMs` | 400 | 100–2000 | |
| `maxClipMs` | 120000 | 30–600 s | ochrona przed wentylatorem |
| `autoStopAt` | 09:00 | — | albo `maxSessionHours = 10` |
| `retentionDays` | 30 | 1–365 / nigdy | ulubione nigdy nie kasowane |

Wszystko w DataStore, edytowalne w Settings, z przyciskiem „Przywróć domyślne".

---

## 6. Odporność (to jest miejsce, gdzie takie apki zwykle padają)

1. **Przerwanie nagrywania.** Zarejestruj `AudioManager.registerAudioRecordingCallback` i reaguj na `AudioRecordingConfiguration.isClientSilenced` — system może wyciszyć strumień bez błędu, dostajesz same zera i „cicha noc" w wynikach. Gdy wyciszony: pokaż w notyfikacji ostrzeżenie i próbuj wznowić.
2. **`read()` zwraca błąd.** `ERROR_INVALID_OPERATION` / `ERROR_DEAD_OBJECT` → zwolnij `AudioRecord`, odczekaj (backoff 1 s, 2 s, 5 s, 10 s, max 30 s), utwórz od nowa, kontynuuj tę samą sesję. Zaloguj zdarzenie do `Session.interruptions`.
3. **Detekcja „martwego" strumienia.** Watchdog: jeśli przez 60 s wszystkie ramki mają dokładnie `rms == 0`, traktuj jako awarię i zrestartuj capture.
4. **WakeLock.** `PARTIAL_WAKE_LOCK` trzymany przez cały czas sesji, mimo że aktywny `AudioRecord` zwykle wystarcza. Na części ROM-ów nie wystarcza.
5. **Brak miejsca.** Przed każdym klipem sprawdź `StatFs`; poniżej 200 MB — zatrzymaj sesję i powiadom, nie wal wyjątkiem.
6. **Crash w trakcie nocy.** Klip zapisuj przyrostowo (muxer trzyma otwarty plik); przy starcie apki wykryj „osierocone" wpisy w Room bez `endedAt` i spróbuj je naprawić / oznaczyć.

---

## 7. Model danych (Room)

```kotlin
@Entity data class Session(
    @PrimaryKey val id: String,        // UUID
    val startedAt: Long, val endedAt: Long?,
    val noiseFloorDb: Float,
    val clipCount: Int,
    val interruptions: Int,
    val settingsSnapshot: String       // JSON — żeby wiedzieć, przy jakich progach powstało
)

@Entity data class Clip(
    @PrimaryKey val id: String,
    val sessionId: String,
    val startedAt: Long,
    val durationMs: Long,
    val filePath: String,
    val peakDb: Float, val meanDb: Float,
    val voicedMs: Long,
    val vadScore: Float?,              // faza 3
    val transcript: String?,           // faza 3
    val isFavorite: Boolean = false
)
```

Indeks na `(sessionId, startedAt)`. Kasowanie klipu = usunięcie pliku + wiersza (transakcyjnie, plik najpierw).

---

## 8. UI

**Home** — duży przycisk Start/Stop; pod nim, gdy sesja trwa: live meter (poziom + linia tła + linia progu, `Canvas` w Compose, odświeżanie ~10 Hz, nie 50), czas trwania, licznik klipów, ostrzeżenia (bateria/ładowarka/miejsce). Ekran nie musi być włączony — ale dodaj `keepScreenOn` jako opcję dla debugowania.

**Kalibracja** — 15 s pomiaru ciszy w sypialni, pokazuje zmierzone tło i proponuje `triggerDeltaDb`. Potem 5 s „powiedz coś cicho, jak przez sen" i weryfikuje, czy przy tym progu by się wyzwoliło. To rozwiązuje główny problem z komercyjnymi apkami.

**Sesje** — lista nocy: data, długość, liczba klipów, mini-oś czasu z zaznaczonymi zdarzeniami.

**Klipy** — odtwarzacz z waveformem (wystarczy tablica peaków liczona przy zapisie i trzymana obok pliku, nie dekoduj ponownie), share, ulubione, kasowanie, transkrypcja.

**Settings** — parametry z tabeli §5, retencja, eksport, diagnostyka.

---

## 9. Fazy

### Faza 0 — szkielet
- Projekt, version catalog, Compose, uprawnienia runtime, FGS z notyfikacją.
- `AudioCapture` bez bramki, live meter na ekranie, opcjonalny dump całej sesji do WAV (do debugowania).
- **Kryteria akceptacji:** serwis żyje 8 h przy zablokowanym ekranie; meter reaguje; brak ANR.

### Faza 1 — bramka i klipy (MVP)
- `RingBuffer`, `NoiseFloorTracker`, `Gate`, `AacEncoder`, Room, lista klipów, odtwarzanie.
- **Kryteria akceptacji:**
  - Test syntetyczny: 60 s ciszy z jednym 2-sekundowym burstem w środku → dokładnie 1 klip, długość ≈ 2 s + preRoll + hangover, pierwsza próbka bursta obecna w pliku.
  - Test na żywo: cicha wypowiedź z 2 m odległości jest złapana w całości, łącznie z pierwszym słowem.
  - Noc w cichym pokoju bez mówienia → 0–3 klipy, < 5 MB.

### Faza 2 — użyteczność i odporność
- Settings, kalibracja, auto-stop, retencja (`RetentionWorker`), restart po przerwaniu, watchdog, ostrzeżenia o baterii/miejscu, eksport sesji do ZIP.
- **Kryteria akceptacji:** wymuszone przerwanie (odbierz połączenie w trakcie) → sesja wznawia się w < 10 s i kontynuuje; auto-stop działa; retencja nie kasuje ulubionych.

### Faza 3 — inteligencja
- **Silero VAD** (ONNX Runtime Mobile, model 16 kHz, chunki 512 sampli). Dwustopniowo: tani gate RMS wybudza VAD, VAD potwierdza mowę. Klipy poniżej `vadThreshold` (default 0.5) trafiają do „Odrzucone" zamiast być kasowane — przez pierwszy tydzień chcesz widzieć, co filtr wyrzuca. Uwaga: Silero potrafi klasyfikować chrapanie jako mowę, więc nie ustawiaj tego jako twardego filtra.
- **Transkrypcja** — whisper.cpp przez JNI, model `base` lub `small` multilingual (polski). `TranscribeWorker` z constraintami `requiresCharging` + `requiresDeviceIdle`, więc mieli rano na ładowarce. Fallback: przycisk „transkrybuj teraz" per klip.
- **Telegram** — `TelegramWorker`, `sendVoice` do listy chat_id, token w `EncryptedSharedPreferences`. Domyślnie **digest poranny** (jedna wiadomość z podsumowaniem + top 3 klipy), nie powiadomienie o każdym zdarzeniu w środku nocy.

---

## 10. Testy

**Unit (JVM), bez Androida:**
- `Gate` — karmiony syntetycznym PCM: cisza, pojedynczy burst, dwa bursty w odstępie < `mergeGapMs` (→ 1 klip), dwa w odstępie > (→ 2 klipy), narastający szum (tło nadąża, brak fałszywego triggera), trzask 30 ms (odrzucony przez `minVoicedMs`).
- `NoiseFloorTracker` — tło nie rośnie podczas długiej wypowiedzi.
- `RingBuffer` — wraparound, zrzut w każdej pozycji.

Wyodrębnij te klasy jako **czysty Kotlin bez zależności od Androida** — cała logika bramkowania musi być testowalna bez emulatora. To jest najważniejsza decyzja architektoniczna w tym projekcie.

**Instrumented:** cykl życia serwisu, zapis/odczyt Room, integralność pliku `.m4a` (`MediaExtractor` odczytuje deklarowaną długość).

**Manualne:** odtwórz z drugiego telefonu nagrania mówienia przez sen na różnych głośnościach z 1/2/3 m.

---

## 11. Uwagi końcowe dla implementującego

- Nie dodawaj `INTERNET` do manifestu przed fazą 3. Apka nagrywająca sypialnię bez dostępu do sieci to sensowny domyślny kontrakt.
- Logi diagnostyczne (poziom, tło, przejścia stanów) zapisuj do pliku rotowanego w `cacheDir` — po pierwszej nocy będziesz je czytał, żeby dostroić progi. Bez tego strojenie to zgadywanka.
- Jeśli w pokoju śpi druga osoba — poinformuj ją, że nagrywanie działa. Nagrywanie siebie jest oczywiście legalne, cudzych wypowiedzi bez wiedzy już niekoniecznie.
- Zacznij od fazy 1 i przespaj z nią jedną noc, zanim napiszesz cokolwiek z fazy 2. Realne dane z jednej nocy powiedzą więcej o dobrych wartościach domyślnych niż cała ta tabela.
