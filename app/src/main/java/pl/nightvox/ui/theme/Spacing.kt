package pl.nightvox.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Skala odstępów. Pięć wartości zamiast dowolnych liczb w każdym pliku — to jest cała
 * różnica między „ekran ma marginesy" a „ekran wygląda na złożony z przypadku".
 */
object Spacing {
    /** Wewnątrz jednego elementu: ikona ↔ tekst. */
    val tiny = 4.dp

    /** Między wierszami tej samej rzeczy. */
    val small = 8.dp

    /** Domyślny odstęp wewnątrz karty. */
    val medium = 12.dp

    /** Padding kart i odstęp między nimi. */
    val large = 16.dp

    /** Marginesy ekranu i przerwy między sekcjami. */
    val screen = 20.dp

    /** Oddech nad nowym blokiem treści. */
    val section = 28.dp
}
