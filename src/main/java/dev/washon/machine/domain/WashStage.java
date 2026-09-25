package dev.washon.machine.domain;

/**
 * Which part of the wash cycle a running machine is in (P2-002), inferred
 * from the stage-prefixed OCR countdown convention documented on
 * {@link OcrReading}. Lower-case on purpose, matching {@link MachineState}:
 * {@code EnumType.STRING} persists {@code name()}, and these exact strings
 * are the stored VARCHAR values and the API wire values.
 *
 * <p>Null (not a member of this enum) means "not in_use" or "OCR text didn't
 * use the stage convention" -- both are graceful degradation, not an error.
 */
public enum WashStage {
    wash,
    rinse,
    spin;

    /** Maps the convention's single-letter code (W/R/S) to its stage. Null if unrecognised. */
    static WashStage fromCode(String code) {
        return switch (code) {
            case "W" -> wash;
            case "R" -> rinse;
            case "S" -> spin;
            default -> null;
        };
    }
}
