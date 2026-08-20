package pl.nightvox.ui.sessions

import pl.nightvox.data.db.ClipEntity

/** Jeden słupek histogramu ocen mowy. */
data class ScoreBucket(
    val from: Float,
    val to: Float,
    /** Klipy w tym przedziale, które przeszły filtr. */
    val kept: Int,
    /** Klipy w tym przedziale, które trafiły do kosza. */
    val discarded: Int,
) {
    val total: Int get() = kept + discarded
}

/**
 * Statystyki nocy liczone z listy klipów — czysty Kotlin, bez Androida, więc dają się
 * przetestować na JVM.
 *
 * Histogram ocen jest tu najważniejszy: przy stu klipach na noc pojedyncze liczby nic nie
 * mówią, a rozkład od razu pokazuje, czy filtr widzi dwie różne rzeczy (dwa skupiska po obu
 * stronach progu), czy jedną mgłę rozłożoną wokół niego. W tym drugim przypadku przesuwanie
 * progu niczego nie naprawi i trzeba poprawiać same cechy.
 */
object NightStats {

    const val BUCKETS = 10

    /** Pusty, gdy żaden klip nie ma oceny — noc sprzed filtru mowy. */
    fun histogram(clips: List<ClipEntity>): List<ScoreBucket> {
        val scored = clips.filter { it.vadScore != null }
        if (scored.isEmpty()) return emptyList()

        val kept = IntArray(BUCKETS)
        val discarded = IntArray(BUCKETS)
        for (clip in scored) {
            val index = bucketOf(clip.vadScore!!)
            if (clip.isDiscarded) discarded[index]++ else kept[index]++
        }
        return List(BUCKETS) { i ->
            ScoreBucket(
                from = i.toFloat() / BUCKETS,
                to = (i + 1).toFloat() / BUCKETS,
                kept = kept[i],
                discarded = discarded[i],
            )
        }
    }

    fun bucketOf(score: Float): Int = (score * BUCKETS).toInt().coerceIn(0, BUCKETS - 1)

    /** Mediana ocen; `null`, gdy nic nie było oceniane. */
    fun medianScore(clips: List<ClipEntity>): Float? {
        val scores = clips.mapNotNull { it.vadScore }.sorted()
        return if (scores.isEmpty()) null else scores[scores.size / 2]
    }

    /**
     * Najdłuższa przerwa między zdarzeniami — „ile trwała najspokojniejsza część nocy”.
     * Liczona razem z odrzuconymi, bo cisza to brak *jakiegokolwiek* zdarzenia.
     */
    fun longestQuietGapMs(clips: List<ClipEntity>, startedAt: Long, endedAt: Long): Long {
        if (endedAt <= startedAt) return 0
        val times = clips.map { it.startedAt }.filter { it in startedAt..endedAt }.sorted()
        var previous = startedAt
        var longest = 0L
        for (time in times) {
            longest = maxOf(longest, time - previous)
            previous = maxOf(previous, time)
        }
        return maxOf(longest, endedAt - previous)
    }
}
