package com.hatchlybox.app

/**
 * Fast explicit routing; ambiguous requests stay with the user. What a creation becomes follows the engine's
 * route.sh: web by default (it runs everywhere, and goes on the phone with one tap); an Android app when it needs
 * the phone itself; a Godot game for a real game; a system the request names.
 */
object IntentRouter {
    private val PhoneNeeds = Regex("""(^|[^a-z0-9])(android|apk|google play|play store|home ?screen|lock ?screen|launcher|wallpaper|notifications?|notify|notifies|reminders?|remind me|alarms?|wake me|in the background|runs? in background|sensors?|step counter|count(s|ing)? (my )?steps|pedometer|accelerometer|gyroscope|compass|camera|take (a )?photos?|scanner|scan (a |the )?(qr|barcode|document|receipt)s?|qr (code )?scanner|barcode|bluetooth|nfc|gps|location|where i am|nearby|calls?|sms|text messages?|contacts|vibrat(e|ion)|flashlight|torch|microphone|record (audio|sound|voice)|battery|offline maps|sleep tracking|share sheet|whatsapp|notificaç(ão|ões)|notificaci(ón|ones)|lembretes?|recordatorios?|alarmes?|alarmas?|câmera|cámara)($|[^a-z0-9])""")
    private val RealGame = Regex("""(^|[^a-z0-9])(3d|physics|platformer|shooter|fps|first[- ]person|third[- ]person|rpg|racing|racer|open[- ]world|strategy|rts|tower defen[cs]e|roguelike|roguelite|metroidvania|survival|sandbox|levels|sprites?|tilemap|bullet hell|fighting|multiplayer|godot|estrat[eé]gia|estrategia|carreras|corridas|plataformas|niveles|n[ií]veis|f[ií]sica|disparos|tiros)($|[^a-z0-9])""")
    private val MakePhrase = Regex("""\b(make|build|create|design|develop|generate|code|program)\b(\s+[\w'-]+){0,5}?\s+(apps?|application|games?|widgets?|websites?|site|web ?app|webpage|landing page|tool|program|launcher|plugin|extension|bot|script|tracker|calculator)\b""")
    private val DoStart = Regex("""^(please\s+|can you\s+|could you\s+|hey,?\s+)?(open|launch|go to|start|run|call|ring|dial|text|message|send|reply|answer|whatsapp|email|mail|play|pause|resume|stop|skip|turn|switch|set|put|show me|check|find|search|look up|read|tap|press|click|scroll|close|lock|mute|unmute|share|take|book|order|navigate|install|uninstall|enable|disable|increase|decrease|raise|lower|record|copy|paste|watch|listen|go back|go home)\b""")
    private fun makes(idea: String) = MakePhrase.containsMatchIn(idea.lowercase())

    /** The modes a request names in so many words, "do" for using the phone, or null when the agent should decide. */
    fun classify(idea: String): String? {
        val translated = idea.lowercase(java.util.Locale.ROOT).trim()
            .replace(Regex("""\b(criar|crie|cria|crear|crea|construir|fazer|faz|haz)\b"""), "create")
            .replace(Regex("""\b(aplicativo|aplicação|aplicacion|aplicación)\b"""), "app")
            .replace(Regex("""\b(jogo|juego)\b"""), "game")
        val t = translated
        if (Regex("""^(what|why|how|who|when|where|which|explain|tell me|o que|como|por que|por qué|qué)\b""").containsMatchIn(t)) return "chat"
        fun has(vararg words: String) = words.any { Regex("""(^|[^a-z0-9])${Regex.escape(it)}($|[^a-z0-9])""").containsMatchIn(t) }
        if (Regex("""^(don't|do not|never|not now)\b""").containsMatchIn(t)) return "chat"
        val make = makes(t)
        val doing = DoStart.containsMatchIn(t)
        // "Open Notes and write a shopping list" uses an app; "start a new game app" is ambiguous: the agent decides.
        if (doing) return if (make || Regex("""\bnew\s+([\w'-]+\s+){0,2}(app|game|widget|website)""").containsMatchIn(t)) null else "do"
        val build = make
        return when {
            has("widget", "widgets") && build -> "widget"
            build && has("omarchy", "hyprland", "waybar") -> "omarchy"
            build && has("windows", "win11", "win10", ".exe") -> "windows"
            build && has("macos", "mac app", "ios", "iphone", "ipad", "raspberry pi", "arduino", "esp32", "steam deck", "playstation", "xbox", "nintendo") -> "other"
            build && has("linux") && !has("android") -> "linux"
            build && has("website", "web app", "webapp", "web page", "webpage", "landing page", "homepage", "browser game") -> "web"
            !build && (t.endsWith("?") || Regex("""^(what|why|how|who|when|where|which|is|are|does|do you|can you tell|explain|tell me|hi|hello|hey|thanks)\b""").containsMatchIn(t)) -> "chat"
            build && has("game", "games") -> if (RealGame.containsMatchIn(t) || has("android", "apk")) "game" else "web"
            build && PhoneNeeds.containsMatchIn(t) -> "app"
            build -> "web" // Unspecified: a web app, which runs everywhere and goes on the phone with one tap.
            else -> null
        }
    }

}
