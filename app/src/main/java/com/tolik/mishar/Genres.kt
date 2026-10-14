package com.tolik.mishar

/**
 * Spotify / Last.fm give free-text tags ("israeli rock", "dance pop", ...).
 * We fold them into a short list of genres that make sense for the game.
 */
object Genres {

    class Bucket(val label: String, private val keys: List<String>) {
        fun matches(tags: String) = keys.any { tags.contains(it) }
    }

    val buckets = listOf(
        Bucket("פופ", listOf("pop")),
        Bucket("רוק ומטאל", listOf("rock", "metal", "punk", "grunge", "hardcore", "alternative", "new wave")),
        Bucket("היפ הופ וראפ", listOf("hip hop", "hip-hop", "hiphop", "rap", "trap", "drill", "grime")),
        Bucket("מזרחית וים תיכוני", listOf("mizrahi", "mizrahit", "mediterranean", "middle eastern", "arabic", "greek", "oriental")),
        Bucket("אלקטרוני ודאנס", listOf("electronic", "edm", "house", "techno", "trance", "dance", "dubstep", "drum and bass")),
        Bucket("R&B, סול ופאנק", listOf("r&b", "rnb", "soul", "funk", "motown", "disco")),
        Bucket("ג'אז ובלוז", listOf("jazz", "blues", "swing")),
        Bucket("פולק וקאנטרי", listOf("folk", "country", "singer-songwriter", "acoustic", "americana", "bluegrass")),
        Bucket("רגאיי ולטיני", listOf("reggae", "latin", "reggaeton", "salsa", "dancehall", "bossa", "samba")),
        Bucket("קלאסית ופסקולים", listOf("classical", "opera", "soundtrack", "orchestra"))
    )
}
