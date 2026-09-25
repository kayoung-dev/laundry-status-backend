package dev.washon.auth.domain;

/**
 * Admin account role.
 *
 * <p>Constants are lower-case on purpose: {@code EnumType.STRING} persists
 * {@code name()}, and these exact strings are the stored VARCHAR values and
 * the API wire values inherited from the original Prisma enum.
 */
public enum AdminRole {
    super_admin,
    site_admin,
    technician,
    read_only
}
