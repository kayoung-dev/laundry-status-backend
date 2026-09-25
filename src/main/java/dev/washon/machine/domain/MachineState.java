package dev.washon.machine.domain;

/**
 * Operational state of a machine (event log, current state, student reports).
 *
 * <p>Constants are lower-case on purpose: {@code EnumType.STRING} persists
 * {@code name()}, and these exact strings are the stored VARCHAR values and
 * the API wire values inherited from the original Prisma enum.
 */
public enum MachineState {
    available,
    in_use,
    done,
    maintenance,
    error,
    unknown
}
