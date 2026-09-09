package io.travelos.spring.web.idempotency;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds the {@code Idempotency-Key} request header to a {@code String} controller parameter.
 * Missing or malformed keys are rejected with 400 before the controller runs. Keys are opaque to
 * the client (1-128 chars of {@code [A-Za-z0-9_:.-]}); services scope them by tenant + endpoint.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface IdempotencyKeyHeader {}
