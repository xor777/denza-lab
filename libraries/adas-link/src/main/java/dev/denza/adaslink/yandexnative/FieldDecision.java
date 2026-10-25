package dev.denza.adaslink.yandexnative;

/** A decision at the Yandex-to-SDK input boundary, never a claim about ECU acceptance. */
public final class FieldDecision {
    public enum Kind { FACT, DERIVED, ENUM_MAPPING, SYNTHETIC_ID, SDK_NULL,
        UNKNOWN_MODEL_DEFAULT, UNKNOWN_EXPLICIT_FALSE, UNAVAILABLE }
    public final String field, source, meaning, absence;
    public final Kind kind;
    public FieldDecision(String field, String source, String meaning, String absence, Kind kind) {
        this.field=field;this.source=source;this.meaning=meaning;this.absence=absence;this.kind=kind;
    }
    @Override public String toString(){return field+":"+kind+":"+absence;}
}
