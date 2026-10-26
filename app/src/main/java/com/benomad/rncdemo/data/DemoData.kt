package com.benomad.rncdemo.data

/** Waste stream of a drop-off point; the color is that of its icon on the map. */
enum class WasteStream(val label: String, val color: Int) {
    GLASS("Glass", 0xFF2E7D32.toInt()),
    PACKAGING("Packaging", 0xFFF9A825.toInt()),
    PAPER("Paper", 0xFF1565C0.toInt()),
    TEXTILE("Textile", 0xFF6A1B9A.toInt()),
}

/**
 * A drop-off point (bring bank) to collect. `id` is sent to
 * BeNav in `startGuidance` and comes back unchanged in `onViaPointReached`.
 */
data class GuidancePoint(
    val id: String,
    val number: Int,
    val label: String,
    val stream: WasteStream,
    val lat: Double,
    val lon: Double,
)

/**
 * The two demo scenarios, in central Nice:
 *
 * - **door-to-door collection**: the waste collection vehicle follows a circuit, street by street, in
 *   the Musiciens district (`startCircuitGuidance`, `assets/circuit_nice_musiciens.json`);
 * - **drop-off collection**: 20 drop-off points, point-to-point guidance
 *   (`startGuidance`), from the Musiciens district to the port via Saint-Roch and Carabacel,
 *   in this order or reordered by BeNav.
 *
 * Coordinates: geocoded street addresses, each point on a road open to cars.
 *
 * The first 19 go in `waypoints`, the 20th in `destination`.
 */
object DemoData {

    const val CIRCUIT_ASSET = "circuit_nice_musiciens.json"
    const val CIRCUIT_LABEL = "Door-to-door round, Nice Musiciens — 2.3 km"

    val points: List<GuidancePoint> = listOf(
        p(1, "Avenue Durante", 43.70343, 7.26248),
        p(2, "Rue Gounod", 43.70249, 7.26091),
        p(3, "Rue Berlioz", 43.70088, 7.25944),
        p(4, "Rue Rossini", 43.70083, 7.25752),
        p(5, "Rue Verdi", 43.69960, 7.26004),
        p(6, "Rue Meyerbeer", 43.69750, 7.26034),
        p(7, "Rue de France", 43.69582, 7.26013),
        p(8, "Rue de la Buffa", 43.69708, 7.26187),
        p(9, "Rue Alphonse Karr", 43.69865, 7.26607),
        p(10, "Boulevard Victor Hugo", 43.69966, 7.26523),
        p(11, "Rue de Paris", 43.70505, 7.26840),
        p(12, "Rue Assalit", 43.70637, 7.26834),
        p(13, "Boulevard Raimbaldi", 43.70674, 7.26749),
        p(14, "Boulevard Carabacel", 43.70427, 7.27572),
        p(15, "Boulevard Dubouchage", 43.70163, 7.27115),
        p(16, "Rue Pastorelli", 43.70061, 7.27085),
        p(17, "Rue de l'Hôtel des Postes", 43.70152, 7.27475),
        p(18, "Rue Gioffredo", 43.70177, 7.27627),
        p(19, "Rue Barla", 43.70200, 7.28239),
        p(20, "Rue Cassini", 43.70025, 7.28165),
    )

    /** Streams assigned in rotation: the demo shows all four colors on the map. */
    private fun p(n: Int, label: String, lat: Double, lon: Double) = GuidancePoint(
        id = "dropoff_%02d".format(n),
        number = n,
        label = label,
        stream = WasteStream.values()[(n - 1) % WasteStream.values().size],
        lat = lat,
        lon = lon,
    )
}
