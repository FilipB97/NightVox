# NightVox

Aplikacja Android do nagrywania mówienia przez sen. Nagrywa **tylko zdarzenia dźwiękowe**,
nie całą noc: bramkowanie progiem adaptacyjnym + bufor pre-roll + hangover. Rano dostajesz
listę kilku–kilkudziesięciu klipów po kilkanaście sekund zamiast ośmiu godzin ciszy.

Wszystko zostaje lokalnie. Apka **nie ma uprawnienia `INTERNET`** — nagrania fizycznie nie
mogą opuścić telefonu inaczej niż przez świadome udostępnienie pliku.

Implementacja realizuje [`plan.md`](plan.md). Stan: **fazy 0–2 zrobione**, z fazy 3 działa
**filtr mowy** — własna analiza widmowa, która odsiewa oddech i chrapanie od mówienia.
Silero VAD był zrobiony i został wycofany (natywny crash na urządzeniu docelowym), historia
i powody niżej.

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
konkretny przebieg → sekcja *Artifacts*).

Artefakt zawiera jeden plik: `app-release.apk` (~2,7 MB). Instaluje się na dowolnym
urządzeniu — po wycofaniu ONNX Runtime w aplikacji nie ma już żadnego dużego kodu
natywnego, więc nie ma też powodu filtrować architektur.

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

### Gdy apka się wywali

Niewyłapane wyjątki lądują w logu diagnostycznym razem z modelem telefonu, wersją Androida
i listą ABI, a po ponownym uruchomieniu ekran główny pokazuje kartę z przyciskiem
„Udostępnij log błędu”. Sideload nie ma Play Console, a apka celowo nie ma dostępu do sieci,
więc to jedyna droga, żeby stack trace w ogóle do kogoś dotarł.

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

**Filtr mowy** (`audio/speech/`) jest drugim stopniem: bramka decyduje, *kiedy* nagrywać,
analiza widmowa decyduje, czy to, co nagrano, brzmi jak mowa, czy jak oddech albo chrapanie.
Nic nie kasuje — odsyła do kosza. Szczegóły niżej.

**Tło szumu** (`audio/NoiseFloorTracker.kt`) startuje od mediany z 10 s warm-upu (nie EMA —
trzask ładowarki na starcie nie może zatruć progu na całą noc), potem asymetryczna EMA
aktualizowana **wyłącznie przy zamkniętej bramce**, żeby długa wypowiedź nie podniosła tła
i sama się nie wyciszyła.

### Cała logika decyzyjna jest czystym Kotlinem

`Gate`, `RingBuffer`, `NoiseFloorTracker`, `LevelMeter`, `GateConfig` nie mają żadnej
zależności od Androida — tak samo cały pakiet `audio/speech` (FFT, autokorelacja, cechy
widmowe, scoring), czyli logika, która najłatwiej psuje się po cichu. Dzięki temu testy
przechodzą na JVM w kilka sekund, bez emulatora — łącznie z kryteriami akceptacji fazy 1
z planu:

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
| `mowa dostaje wysoka ocene a oddech i chrapanie niska` | filtr mowy rozdziela trzy syntetyczne sygnały |
| `ocena nie zalezy od glosnosci` | ciche mamrotanie dostaje tę samą ocenę co głośne |
| `wysokie chrapanie nadal nie jest mowa` | chrapanie z tonem 100 Hz (w zakresie głosu) wciąż odpada |
| `ton ponizej 90 hz jest karany a nie nagradzany` | okresowość sama w sobie nie jest dowodem mowy |
| `szept traci czesc oceny ale nie wszystko` | brak tonu krtaniowego nie zeruje oceny |
| `sinus daje szczyt w swoim prazku` / `energia widma…` | FFT: poprawność i skala (Parseval) |
| `histogram rozdziela zapisane od odrzuconych` | statystyki nocy, łącznie z oceną 1,0 na krańcu |
| `przecinek w nazwie pliku jest cytowany` | eksport CSV nie rozjeżdża się na dziwnej nazwie |

Testy instrumentacyjne (`app/src/androidTest`) wymagają urządzenia lub emulatora i
pokrywają integralność `.m4a` (`MediaExtractor` odczytuje zadeklarowaną długość), Room,
retencję i cykl życia serwisu.

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
| filtr mowy | włączony | wł./wył. |
| próg mowy | 0,40 | 0,15–0,80 |
| auto-stop | 09:00 / max 10 h | — |
| `retentionDays` | 30 | 0 (nigdy) – 365 |
| `discardedRetentionDays` | 7 | 1–30 |

Migawka parametrów trafia do `Session.settingsSnapshot`, więc po tygodniu wiadomo, przy
jakich progach powstała każda noc. Ulubione klipy nie są kasowane przez retencję nigdy.

Klipy: AAC-LC 32 kbps mono 16 kHz w `filesDir/clips/{yyyy-MM-dd}/{HHmmss}.m4a`, ok. 240 kB
na minutę. Obok każdego pliku leży `.peaks` — obwiednia liczona przy zapisie, żeby
rysowanie waveformu nie wymagało ponownego dekodowania.

### Filtr mowy — oddech i chrapanie to nie mówienie

Bramka RMS reaguje na **głośność**, a nocą głośniejsze od tła są trzy różne rzeczy. Pierwsza
przespana noc na samej bramce dała 102 klipy przez 7 godzin i na żadnym nie było mowy —
sam oddech i chrapanie. Progiem tego nie da się naprawić: klipy z tej nocy miały szczyty od
−17 dB do −42 dB, więc żadna wartość `triggerDeltaDb` nie przechodzi między klasami.
Rozdziela je nie poziom, tylko **kształt sygnału**.

`pl.nightvox.audio.speech` liczy więc cechy widmowe okno po oknie (1024 próbki, skok 32 ms):

| | oddech | chrapanie | mowa |
|---|---|---|---|
| okresowość (autokorelacja) | brak | silna | silna (głoski dźwięczne) |
| ton podstawowy | — | 25–90 Hz | 85–300 Hz |
| energia powyżej 300 Hz | duża (szum) | mała | duża (formanty) |
| płaskość widma | wysoka | niska | niska |
| zmienność widma między oknami | znikoma | znikoma | duża (artykulacja) |
| rytm obwiedni | ~0,25 Hz | ~0,3 Hz (oddech) | 3–6 Hz (sylaby) |

Żadna pojedyncza cecha nie rozdziela wszystkich trzech — `hiRatio` nie odróżnia oddechu od
mowy, okresowość nie odróżnia chrapania od mowy — więc ocena jest **iloczynem**, nie sumą:
buczenie poniżej 90 Hz musi dać się wyzerować, nawet jeśli akurat dostanie punkty za coś
innego. Wynik 0–1 ląduje w `Clip.vadScore`, klip poniżej progu idzie do kosza (nie do
kasacji), a próg jest suwakiem w Ustawieniach.

Całość to czysty Kotlin: własna FFT radix-2, autokorelacja na sygnale zdecymowanym do 8 kHz
i mała DFT widma obwiedni. Zero kodu natywnego — po historii opisanej niżej to jest świadoma
decyzja, a nie oszczędność. Analiza chodzi tylko wtedy, gdy bramka trzyma otwarty klip, czyli
przez ułamek nocy.

Uczciwe zastrzeżenie: **wagi są zgadnięte, nie wytrenowane**, a testy jeżdżą na sygnałach
syntetycznych, które mają zadane własności — dowodzą, że detektor mierzy to, co deklaruje,
nie że sprawdzi się w konkretnej sypialni. Dlatego ocena każdego klipu (razem z cechami
składowymi) trafia do logu diagnostycznego, klipy odrzucone zostają do odsłuchania, a próg
da się przesunąć bez przebudowy aplikacji.

### Silero VAD — zrobiony i wycofany

Zanim powstał filtr powyżej, VAD z fazy 3 był w pełni zaimplementowany (ONNX Runtime, model
16 kHz w assetach, wynik jako `Clip.vadScore`, klipy poniżej progu do kosza). **Został
usunięty**, bo na urządzeniu docelowym — Galaxy A13 z 32-bitowym Androidem — ONNX Runtime
przewracał proces natywnie przy tworzeniu sesji, ok. 2,5 s po starcie nagrywania.

Log diagnostyczny pokazywał to jednoznacznie: wpis „ładuję model ONNX”, brak wpisu „model
gotowy”, a chwilę później wpis `[startup]`, który wykonuje się wyłącznie przy starcie procesu.
Crash natywny nie przechodzi przez `Thread.setDefaultUncaughtExceptionHandler` ani przez żaden
`catch` w Kotlinie, więc nie da się go ani złapać, ani obejść od strony aplikacji. Diagnoza
wymagałaby `adb logcat` i tombstone'a z tego konkretnego urządzenia.

Bilans wypadł jednoznacznie: nagrywanie jest funkcją, bez której ta aplikacja nie ma sensu, a
VAD dodatkiem. Dodatek, który zabija proces i którego nie da się naprawić bez urządzenia, nie
zarabia na 12 MB kodu natywnego na architekturę. Po usunięciu APK schudło z 74 MB do 2,7 MB i
przestał być wybredny co do architektury. Kolumna `vadScore` została w schemacie i trzyma
dziś ocenę z filtru opisanego wyżej, więc zamiana nie wymagała migracji.

### Kosz „Odrzucone”

Zdarzenia, które nie przeszły przez `minVoicedMs` **ani przez filtr mowy**, domyślnie nie znikają — lądują w
zakładce „Odrzucone” razem z powodem odrzucenia i dają się odsłuchać oraz przywrócić na
zwykłą listę. Dopóki progi nie są dostrojone, najważniejsze pytanie brzmi „czy filtr nie
wycina mowy”, a bez nagrania nie da się na nie odpowiedzieć. Kosz ma własną, krótszą
retencję (domyślnie 7 dni), bo służy do strojenia, a nie do archiwizacji; da się go też
wyłączyć w Ustawieniach.

### Przegląd nocy

Sto zdarzeń na noc to za dużo, żeby przesłuchiwać je po kolei, więc przegląd jest zbudowany
wokół pytania „na co warto spojrzeć”:

- **Oś czasu sesji** — kreska na każde zdarzenie, wysokość to ocena mowy, przygaszone to
  odrzucone, pionowe linie co pełną godzinę. Od jednego spojrzenia widać, czy coś odstaje.
- **Rozkład ocen** — histogram z zaznaczonym progiem tej nocy. Dwa skupiska po obu stronach
  progu znaczą, że filtr widzi dwie różne rzeczy i wystarczy przesunąć próg. Jedna mgła wokół
  progu znaczy, że progiem się tego nie naprawi i trzeba poprawiać same cechy.
- **Sortowanie wg oceny** na liście klipów. W koszu wypycha na górę te odrzucone, przy których
  filtr był najbliżej pomyłki — czyli dokładnie te, które warto sprawdzić.
- **„Poprzedni / następny”** w szczegółach klipu, z licznikiem `12 / 102`. Przeglądanie nie
  wymaga wracania do listy po każdym odsłuchaniu, a „wstecz” nadal wraca do listy, nie odtwarza
  całej trasy.
- **Eksport nocy** do ZIP-a zawiera `klipy.csv` z ocenami i cechami wszystkich zdarzeń
  (razem z odrzuconymi), `podsumowanie.txt` z parametrami tej nocy oraz same pliki audio
  w katalogach `klipy/` i `odrzucone/`. Czas w CSV jest w formacie `yyyy-MM-dd HH:mm:ss`,
  liczby z kropką dziesiętną — plik ma się otworzyć w arkuszu i dać posortować po ocenie.

---

## Czego tu nie ma (faza 3)

- **Sieciowy VAD (Silero)** — patrz wyżej: zrobiony, wycofany po natywnym crashu na
  urządzeniu docelowym. Jego rolę pełni dziś filtr mowy na własnej analizie widmowej.
- **Transkrypcja (whisper.cpp)** — pole `Clip.transcript` czeka; ekran klipu ma sekcję
  „Transkrypcja” z jawną informacją, że to faza 3.
- **Telegram** — wymaga uprawnienia `INTERNET`, które jest w manifeście **zakomentowane**.
  Apka nagrywająca sypialnię bez dostępu do sieci to sensowny domyślny kontrakt i nie warto
  go łamać przed czasem.

## Uwaga

Nagrywanie siebie jest legalne. Nagrywanie cudzych wypowiedzi bez ich wiedzy już
niekoniecznie — jeśli w pokoju śpi ktoś jeszcze, powiedz mu, że apka działa.
