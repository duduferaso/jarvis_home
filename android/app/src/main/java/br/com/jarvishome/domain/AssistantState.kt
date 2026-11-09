package br.com.jarvishome.domain

/** Estados visuais e lógicos do assistente (seção 6.2 do planejamento). */
enum class AssistantState(val label: String) {
    IDLE("Em espera"),
    LISTENING("Ouvindo"),
    THINKING("Pensando"),
    SPEAKING("Falando"),
    HAPPY("Feito"),
    CONFUSED("Não entendi"),
    ERROR("Erro"),
    SLEEPING("Em repouso")
}

enum class ConnectionState(val label: String) {
    UNKNOWN("Verificando…"),
    CONNECTED("Casa conectada"),
    HA_DOWN("Home Assistant indisponível"),
    UNAUTHORIZED("Token recusado"),
    NOT_CONFIGURED("Falta o token (engrenagem)"),
    DISCONNECTED("Sem conexão com a casa")
}
