package dev.washon.common.jpa;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import org.hibernate.annotations.IdGeneratorType;

/**
 * Marks a String primary key that is generated client-side as a CUID v1 --
 * the same format Prisma's {@code @default(cuid())} produced. An id that is
 * already set before persist (e.g. the fixed seed ids) is kept as-is.
 */
@IdGeneratorType(CuidGenerator.class)
@Retention(RUNTIME)
@Target(FIELD)
public @interface CuidId {
}
