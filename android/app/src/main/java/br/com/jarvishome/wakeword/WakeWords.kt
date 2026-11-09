package br.com.jarvishome.wakeword

import br.com.jarvishome.settings.Prefs

/**
 * Palavras de ativação disponíveis. O detector do app foi treinado em inglês, então cada palavra
 * tem várias grafias que imitam a pronúncia brasileira; a calibração escolhe as que reconhecem
 * a voz do usuário. Ranking medido com vozes brasileiras sintéticas (22 palavras testadas):
 * Alexa 5/8, Tony 3/8, Max 5/8 (fraco na voz natural), Jarvis 1/8 (melhora muito com voz real).
 */
object WakeWords {

    class Profile(
        val id: String,
        val display: String,
        val note: String,
        val defaultLines: List<String>,
        val candidates: List<String>
    )

    val JARVIS = Profile(
        "jarvis", "Jarvis", "Padrão. Calibre com a sua voz.",
        listOf(
            "▁JA R VI S @JARVIS", "▁JA VI S @JAVIS", "▁JO VI S @JOVIS",
            "▁JA R VE S @JARVES", "▁JA R ▁VI S @JAR_VIS", "▁SHA R VI S @SHARVIS"
        ),
        listOf(
        "▁JA R VI S @JARVIS",
        "▁JA VI S @JAVIS",
        "▁JO VI S @JOVIS",
        "▁JA R VE S @JARVES",
        "▁JA R ▁VI S @JAR_VIS",
        "▁SHA R VI S @SHARVIS",
        "▁ Z H AR VI S @ZHARVIS",
        "▁JA H VI S @JAHVIS",
        "▁JA VE ES @JAVEES",
        "▁JA H VE ES @JAHVEES",
        "▁ Z HA H VI S @ZHAHVIS",
        "▁SHA H VI S @SHAHVIS",
        "▁SHA VI S @SHAVIS",
        "▁ Z HA VI S @ZHAVIS",
        "▁G ER VI S @GERVIS",
        "▁JA R VE ES @JARVEES",
        "▁JA R B IS @JARBIS",
        "▁CHA R VI S @CHARVIS",
        "▁ D J AR VI S @DJARVIS",
        "▁JA R VI CE @JARVICE",
        "▁JA VI CE @JAVICE",
        "▁JA H R VI S @JAHRVIS",
        "▁JA R VE Z @JARVEZ",
        "▁JA R VI @JARVI",
        "▁JA R V Y @JARVY",
        "▁SHA R VE ES @SHARVEES",
        "▁JA H ▁VI S @JAH_VIS",
        "▁JA ▁VI S @JA_VIS",
        "▁SHA ▁VI S @SHA_VIS",
        "▁ Z HA ▁VI S @ZHA_VIS",
        "▁JA H ▁ VE ES @JAH_VEES",
        "▁JA W VI S @JAWVIS",
        "▁SHA W VI S @SHAWVIS",
        "▁CHA VI S @CHAVIS",
        "▁ X A VI S @XAVIS",
        "▁JA R V ISH @JARVISH",
        "▁JA V ISH @JAVISH",
        "▁JA VI Z @JAVIZ",
        "▁SHA VI Z @SHAVIZ",
        "▁ D J A VI S @DJAVIS",
        "▁ D J A H VI S @DJAHVIS",
        "▁HA R VI S @HARVIS",
        "▁HA VI S @HAVIS",
        "▁JE R VI S @JERVIS",
        "▁JA R VE S T @JARVEST",
        "▁JA V IST @JAVIST",
        "▁G I AR VI S @GIARVIS",
        "▁ D J AR VE ES @DJARVEES"
    )
    )

    val ALEXA = Profile(
        "alexa", "Alexa", "Melhor nos testes. Pode confundir com “ela disse” e com aparelhos Echo.",
        listOf("▁A LE X A @ALEXA", "▁A LE K S A @ALEKSA", "▁E LE X A @ELEXA", "▁A LE X A H @ALEXAH"),
        listOf(
            "▁A LE X A @ALEXA",
            "▁A LE K S A @ALEKSA",
            "▁A LE C S A @ALECSA",
            "▁A LE X A H @ALEXAH",
            "▁A LE X @ALEX",
            "▁A LE X IA @ALEXIA",
            "▁E LE X A @ELEXA",
            "▁A LE K S A H @ALEKSAH",
            "▁A H ▁LE X A @AH_LEXA",
            "▁A LE X I @ALEXI",
            "▁A LE K SE @ALEKSE",
            "▁ U LE X A @ULEXA",
            "▁A LI X A @ALIXA",
            "▁ALL E X A @ALLEXA"
        )
    )

    val TONY = Profile(
        "tony", "Hey Tony", "Segundo melhor nos testes, sem falsos disparos.",
        listOf("▁TO N Y @TONY", "▁TO N I @TONI", "▁TO NE Y @TONEY", "▁TO NE E @TONEE"),
        listOf(
            "▁TO N Y @TONY",
            "▁TO N I @TONI",
            "▁TO N I E @TONIE",
            "▁TO H N Y @TOHNY",
            "▁TO NE Y @TONEY",
            "▁TO NE E @TONEE",
            "▁TA W N Y @TAWNY",
            "▁TO H ▁NE E @TOH_NEE",
            "▁DON Y @DONY",
            "▁TO N IA @TONIA"
        )
    )

    val MAX = Profile(
        "max", "Max", "Curta: aciona fácil, mas pode disparar em conversas.",
        listOf("▁MA X @MAX", "▁MA K S @MAKS", "▁MA X I @MAXI"),
        listOf(
            "▁MA X @MAX",
            "▁MA K S @MAKS",
            "▁MA X I @MAXI",
            "▁MA CK S @MACKS",
            "▁MA K S I @MAKSI",
            "▁MAKE S @MAKES",
            "▁MA X ▁E E @MAX_EE",
            "▁MA C S @MACS",
            "▁MA H K S @MAHKS",
            "▁MA X I E @MAXIE"
        )
    )

    val ALL = listOf(JARVIS, ALEXA, TONY, MAX)

    fun byId(id: String): Profile = ALL.firstOrNull { it.id == id } ?: JARVIS

    fun current(prefs: Prefs): Profile = byId(prefs.wakeWordId)

    /** Troca a palavra: volta às variações padrão dela até o usuário calibrar. */
    fun select(prefs: Prefs, profile: Profile) {
        if (prefs.wakeWordId == profile.id) return
        prefs.wakeWordId = profile.id
        prefs.keywords = profile.defaultLines.joinToString("\n")
        prefs.keywordBoost = 1.5f
        prefs.sensitivity = 8
        prefs.calibratedWord = ""
    }
}
