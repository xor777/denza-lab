package dev.denza.hudsurfaces.probe;

/** The probe's buttons: one per open question of docs/hud-output-architecture.md, and the reset. */
enum Scenario {
    CAMERAS_OFF("cameras-off", "1 · Камеры без навигации"),
    CAMERAS_ON("cameras-on", "2 · Камеры с навигацией"),
    WARNING("warning", "3 · Камера в блоке манёвра"),
    HANDOVER_QUIET("handover-quiet", "4а · Передача: A замолкает"),
    HANDOVER_GAP("handover-gap", "4б · Передача: A уходит"),
    LANES("lanes", "5 · Картинка в окне полос"),
    GPS_ONLY("gps-only", "6 · GPS в окне карты без манёвра"),
    GPS_REFERENCE("gps-reference", "6б · Контроль карты и GPS"),
    GPS_GATE("gps-gate", "6в · Нулевые метры и код манёвра"),
    RESET("reset", "Сбросить HUD");

    /** What `am start ... --es scenario <id>` names. */
    final String id;
    final String title;

    Scenario(String id, String title) {
        this.id = id;
        this.title = title;
    }

    static Scenario byId(String id) {
        for (Scenario scenario : values()) {
            if (scenario.id.equals(id)) {
                return scenario;
            }
        }
        return null;
    }
}
