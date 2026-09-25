package dev.washon.machine.domain;

/**
 * Kind of laundry machine.
 *
 * <p>Constants are lower-case on purpose: {@code EnumType.STRING} persists
 * {@code name()}, and these exact strings are the stored VARCHAR values and
 * the API wire values inherited from the original Prisma enum.
 */
public enum MachineType {
    washer,
    dryer
}
