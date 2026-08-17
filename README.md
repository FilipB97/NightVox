# NightVox

Aplikacja Android do nagrywania mówienia przez sen. Nagrywa **tylko zdarzenia dźwiękowe**,
nie całą noc: bramkowanie progiem adaptacyjnym + bufor pre-roll + hangover. Rano dostajesz
listę kilku–kilkudziesięciu klipów po kilkanaście sekund zamiast ośmiu godzin ciszy.

Wszystko zostaje lokalnie. Apka **nie ma uprawnienia `INTERNET`** — nagrania fizycznie nie
mogą opuścić telefonu inaczej niż przez świadome udostępnienie pliku.

Implementacja realizuje [`plan.md`](plan.md). Stan: **fazy 0–2 zrobione**, z fazy 3 gotowy
jest **Silero VAD**; transkrypcja i Telegram nie są zaczęte — szczegóły niżej.

---

## Szybki start

```bash
# wymagany Android SDK (platform 35, build-tools 35) i JDK 17+
echo "sdk.dir=/ścieżka/do/android-sdk" > local.properties

./gradlew testDebugUnitTest      # testy bramki na JVM, bez emulatora
./gradlew assembleDebug          # APK do wgrania
./gradlew installDebug           # na podłączone urządzenie
```

`assembleRelease` produkuje APK podpisany kluczem debugowym — to build do sideloadu na
własny telefon, nie do dystrybucji.

### Gotowy APK z CI

Każdy push buduje APK i wystawia go jako artefakt `nightvox-apk` (zakładka **Actions** →
konkretny przebieg → sekcja *Artifacts*). W środku są dwa pliki: `app-release.apk` (mniejszy,
z minifikacją — ten do normalnego używania) i `app-debug.apk`.

Klucz debugowy jest trzymany w cache Actions, więc kolejne APK z CI instalują się na wierzch
poprzednich. Nie da się natomiast zainstalować APK z CI na wierzch zbudowanego lokalnie (i
odwrotnie) — to inne klucze, Android odrzuci taką aktualizację. Wtedy trzeba najpierw
odinstalować starą wersję.

### Pierwsze uruchomienie

1. Przyznaj mikrofon i notyfikacje.
2. **Zrób kalibrację** (ekran „Kalibracja”): 15 s ciszy w sypialni + 5 s cichej wypowiedzi.
   Domyślne 12 dB to punkt startowy, nie prawda objawiona — realny próg zależy od pokoju.
3. Zwolnij apkę z optymalizacji baterii (karta na ekranie głównym).
4. Podłącz ładowarkę, naciśnij „Zacznij nasłuchiwać”, zablokuj ekran.

Rano: lista klipów, a w Ustawieniach → Diagnostyka log z przejściami bramki. Ten log jest
jedynym sensownym wejściem do strojenia progów po pierwszej nocy.

---

## Jak to działa

```
AudioCapture ──► Channel<Frame> ──► Gate ──► Channel<GateAction> ──► ClipWriter
(wątek urgent-audio)              (pipeline)                        (wątek enkodera)
```

Enkodowanie nigdy nie dzieje się na wątku odczytu: `MediaCodec` potrafi zablokować się na
kilkadziesiąt ms, a wtedy `AudioRecord` gubi próbki.

**Bramka** (`audio/Gate.kt`) to maszyna stanów `WARMUP → IDLE → RECORDING → HANGOVER →
LINGER`. `LINGER` to okno scalania: klip jest już logicznie zamknięty, ale plik został
otwarty — jeśli w ciągu `mergeGapMs` padnie nowy trigger, ramki z tego okna dopisują się do
tego samego pliku. Bez tego jedna wypowiedź z pauzą rozsypuje się na pięć klipów po 2 s.

**Pre-roll** (`audio/RingBuffer.kt`) trzyma ostatnie `preRollMs` audio niezależnie od stanu
bramki. Przy triggerze cała zawartość leci do enkodera jako pierwsza — dlatego pierwsza
sylaba nie ginie. Okno jest zakotwiczone w momencie triggera, więc realnej ciszy sprzed
wypowiedzi jest `preRoll − attackFrames·20 ms`; test `GateTest` sprawdza to co do próbki.

**Tło szumu** (`audio/NoiseFloorTracker.kt`) startuje od mediany z 10 s warm-upu (nie EMA —
trzask ładowarki na starcie nie może zatruć progu na całą noc), potem asymetryczna EMA
aktualizowana **wyłącznie przy zamkniętej bramce**, żeby długa wypowiedź nie podniosła tła
i sama się nie wyciszyła.

### Cała logika decyzyjna jest czystym Kotlinem

`Gate`, `RingBuffer`, `NoiseFloorTracker`, `LevelMeter`, `GateConfig` nie mają żadnej
zależności od Androida — łącznie z `VadChunker`, czyli logiką, która najłatwiej psuje się
po cichu. Dzięki temu 45 testów przechodzi na JVM w kilka sekund, bez emulatora —
łącznie z kryteriami akceptacji fazy 1 z planu:

| Test | Co sprawdza |
|---|---|
| `cicha noc nie produkuje klipow` | 60 s szumu tła → 0 klipów |
| `pojedynczy burst w ciszy daje dokladnie jeden klip` | 60 s ciszy + 2 s bursta → 1 klip ≈ preRoll + 2 s + hangover |
| `pre-roll zawiera audio sprzed triggera bit w bit` | zawartość klipu = dokładny wycinek sygnału źródłowego |
| `dwa bursty blizej niz mergeGap…` / `…dalej niz…` | scalanie: 1 klip vs 2 klipy |
| `narastajacy szum nie wyzwala bramki` | tło rośnie o 25 dB przez 2 min → 0 fałszywych triggerów |
| `krotki trzask jest odrzucany przez minVoicedMs` | 100 ms trzasku → `DiscardClip(TOO_SHORT)` |
| `ciagly halas jest ciety na maxClipMs` | 45 s ciągłego dźwięku → kilka klipów, żaden dłuższy niż limit |
| `tlo nie rosnie podczas dlugiej wypowiedzi` | 30 s mówienia → tło stoi |
| `odliczanie warm-upu idzie za ramkami a nie zegarem` | warm-up liczony przetworzonymi ramkami, nie czasem od startu |
| `kontekst kolejnego chunka to ogon poprzedniego` | układ wejścia VAD: 64 próbki kontekstu + 512 chunka |
| `krotka mowa w dlugiej ciszy przezywa jako maksimum` | `vadScore` to maksimum, nie średnia |

Testy instrumentacyjne (`app/src/androidTest`) wymagają urządzenia lub emulatora i
pokrywają integralność `.m4a` (`MediaExtractor` odczytuje zadeklarowaną długość), Room,
retencję, cykl życia serwisu oraz Silero VAD na prawdziwym ONNX Runtime.

Ograniczenie testów VAD, o którym trzeba wiedzieć: sygnał jest **syntetyczny** (model
źródło-filtr), a Silero jest trenowany na prawdziwej mowie. Testy potwierdzają, że wrapper
jest podpięty poprawnie — zwłaszcza kontekst chunków — ale **nie** mierzą skuteczności VAD
na mamrotaniu przez sen. To da się ocenić dopiero na nagraniach z kosza „Odrzucone”.

---

## Odporność

Rzeczy, na których takie apki zwykle padają — i co z nimi robi NightVox:

| Problem | Rozwiązanie |
|---|---|
| System wycisza strumień bez błędu (rozmowa, inna apka) | `registerAudioRecordingCallback` + `isClientSilenced`, ostrzeżenie w notyfikacji i w UI |
| `read()` zwraca `ERROR_DEAD_OBJECT` | zwolnienie i odtworzenie `AudioRecord`, backoff 1/2/5/10/30 s, ta sama sesja leci dalej |
| Martwy strumień (same zera, bez błędu) | watchdog: 60 s dokładnych zer → restart capture |
| Producencki AGC/NS „wyrównuje” mamrotanie do tła | `VOICE_RECOGNITION` → `UNPROCESSED` → `MIC`, jawne wyłączenie `NoiseSuppressor`/`AutomaticGainControl` |
| ROM ubija proces | `PARTIAL_WAKE_LOCK` na całą sesję, prompt o optymalizację baterii, sekcja „Rozwiązywanie problemów” z linkiem do dontkillmyapp.com |
| Brak miejsca | `StatFs` przed każdym klipem i co 30 s; poniżej 200 MB sesja kończy się z powiadomieniem, nie wyjątkiem |
| Crash w środku nocy | przy starcie apki: sesje bez `endedAt` dostają znacznik z ostatniego klipu, wiersze bez plików znikają, puste sesje są kasowane |
| Start FGS z tła (API 34+) | serwis startuje wyłącznie z widocznego Activity; odmowa systemu jest łapana i pokazywana zamiast cichej porażki |

Kasowanie klipu usuwa **najpierw plik, potem wiersz**. Odwrotna kolejność zostawia po
crashu osierocone pliki, których nikt już nie znajdzie.

---

## Struktura

```
app/src/main/java/pl/nightvox/
├── audio/      Frame, RingBuffer, LevelMeter, NoiseFloorTracker, Gate, GateConfig  (czysty Kotlin)
│               AudioCapture                                                        (Android)
├── encode/     AacEncoder (MediaCodec+MediaMuxer), ClipWriter, Waveform, WavDumpWriter
├── service/    RecorderService (FGS microphone), RecorderState, NotificationHelper
├── data/       db/ (Room), ClipRepository, SettingsStore, NightVoxSettings, SessionExporter
├── work/       RetentionWorker
├── util/       DiagnosticsLog, Format, SystemChecks, Sharing
└── ui/         home/, sessions/, clip/, settings/, calibration/, components/, theme/
```

Bez frameworka DI — `AppContainer` w zupełności wystarcza przy tej liczbie obiektów.

## Parametry (Ustawienia)

| Parametr | Default | Zakres |
|---|---|---|
| `triggerDeltaDb` | 12 dB | 6–24 |
| `attackFrames` | 3 (60 ms) | 1–10 |
| `preRollMs` | 3000 | 1000–6000 |
| `hangoverMs` | 4000 | 1000–10000 |
| `mergeGapMs` | 2000 | 0–5000 |
| `minVoicedMs` | 400 | 100–2000 |
| `maxClipMs` | 120 s | 30–600 s |
| `vadEnabled` | włączony | — |
| `vadThreshold` | 0.5 | 0.1–0.9 |
| auto-stop | 09:00 / max 10 h | — |
| `retentionDays` | 30 | 0 (nigdy) – 365 |
| `discardedRetentionDays` | 7 | 1–30 |

Migawka parametrów trafia do `Session.settingsSnapshot`, więc po tygodniu wiadomo, przy
jakich progach powstała każda noc. Ulubione klipy nie są kasowane przez retencję nigdy.

Klipy: AAC-LC 32 kbps mono 16 kHz w `filesDir/clips/{yyyy-MM-dd}/{HHmmss}.m4a`, ok. 240 kB
na minutę. Obok każdego pliku leży `.peaks` — obwiednia liczona przy zapisie, żeby
rysowanie waveformu nie wymagało ponownego dekodowania.

### Silero VAD (faza 3)

Drugi stopień detekcji: tania bramka RMS wybudza sieć, sieć ocenia, czy to naprawdę mowa.
Model (`silero_vad_16k.onnx`, 1,26 MB, MIT) leży w `assets` i chodzi lokalnie przez ONNX
Runtime — apka nadal nie ma uprawnienia `INTERNET`. Analiza kosztuje tylko w trakcie
nagrywania klipu, bo VAD dostaje ten sam PCM co enkoder, na tym samym wątku.

Wynik (`Clip.vadScore`) to **maksimum** prawdopodobieństwa w klipie, nie średnia:
mamrotanie przez sen to zwykle dwa słowa w kilkusekundowym nagraniu, więc średnia
rozmyłaby je do zera. Klip poniżej progu trafia do kosza „Odrzucone” — **nigdy nie jest
kasowany** — skąd da się go odsłuchać i przywrócić. Silero potrafi wziąć chrapanie za mowę
i przegapić ciche mamrotanie, więc to filtr miękki, nie wyrok.

**Uwaga dla modyfikujących:** model v5 wymaga wejścia `kontekst (64 próbki) + chunk (512)`
= 576 próbek, gdzie kontekst to ogon poprzedniego chunka. Karmiony samym chunkiem zwraca
~0.001 **na wszystko**, łącznie z mową — psuje się bezobjawowo. Dlatego to jest osobna
czysta klasa `VadChunker` z własnymi testami, a nie kilka linijek w wrapperze ONNX.

ONNX Runtime wnosi ok. 70 MB natywnych bibliotek na cztery architektury, więc APK jest
ograniczony do `arm64-v8a` (release) i dodatkowo `x86_64` w debugu, żeby działał emulator
CI. Release waży przez to ~22 MB zamiast ~130 MB. Urządzenie 32-bitowe wymaga dołożenia
`armeabi-v7a` w `app/build.gradle.kts`.

### Kosz „Odrzucone”

Zdarzenia, które nie przeszły przez `minVoicedMs`, domyślnie **nie znikają** — lądują w
zakładce „Odrzucone” razem z powodem odrzucenia i dają się odsłuchać oraz przywrócić na
zwykłą listę. Dopóki progi nie są dostrojone, najważniejsze pytanie brzmi „czy filtr nie
wycina mowy”, a bez nagrania nie da się na nie odpowiedzieć. Kosz ma własną, krótszą
retencję (domyślnie 7 dni), bo służy do strojenia, a nie do archiwizacji; da się go też
wyłączyć w Ustawieniach.

---

## Czego tu nie ma (reszta fazy 3)

- **Transkrypcja (whisper.cpp)** — pole `Clip.transcript` czeka; ekran klipu ma sekcję
  „Transkrypcja” z jawną informacją, że to faza 3.
- **Telegram** — wymaga uprawnienia `INTERNET`, które jest w manifeście **zakomentowane**.
  Apka nagrywająca sypialnię bez dostępu do sieci to sensowny domyślny kontrakt i nie warto
  go łamać przed czasem.

## Uwaga

Nagrywanie siebie jest legalne. Nagrywanie cudzych wypowiedzi bez ich wiedzy już
niekoniecznie — jeśli w pokoju śpi ktoś jeszcze, powiedz mu, że apka działa.
