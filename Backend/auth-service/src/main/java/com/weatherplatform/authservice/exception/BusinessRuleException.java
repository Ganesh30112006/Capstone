package com.weatherplatform.authservice.exception;

/**
 * A request that is well-formed and authorized but would violate a platform
 * invariant — for example removing the last administrator.
 *
 * Surfaces as 409 Conflict: the client did nothing wrong syntactically, the
 * current state simply does not permit the operation.
 */
public class BusinessRuleException extends RuntimeException {

    public BusinessRuleException(String message) {
        super(message);
    }
}
