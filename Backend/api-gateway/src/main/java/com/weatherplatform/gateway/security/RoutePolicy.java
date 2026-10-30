package com.weatherplatform.gateway.security;

import org.springframework.http.HttpMethod;
import org.springframework.util.AntPathMatcher;

import java.util.List;
import java.util.Locale;

/**
 * A single "METHOD /ant/pattern" authorization rule.
 *
 * A method of "*" matches any HTTP method. Parsing is done once at startup so
 * the request path is only matched against pre-compiled rules.
 */
public record RoutePolicy(String method, String pattern) {

    private static final AntPathMatcher MATCHER = new AntPathMatcher();
    private static final String ANY_METHOD = "*";

    public static RoutePolicy parse(String rule) {
        String trimmed = rule == null ? "" : rule.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Authorization rule must not be blank");
        }

        String[] parts = trimmed.split("\\s+", 2);
        if (parts.length == 1) {
            // A bare pattern (no method) applies to every method.
            return new RoutePolicy(ANY_METHOD, parts[0]);
        }
        return new RoutePolicy(parts[0].toUpperCase(Locale.ROOT), parts[1]);
    }

    public static List<RoutePolicy> parseAll(List<String> rules) {
        return rules == null ? List.of() : rules.stream().map(RoutePolicy::parse).toList();
    }

    public boolean matches(HttpMethod requestMethod, String path) {
        if (!ANY_METHOD.equals(method) && (requestMethod == null || !method.equals(requestMethod.name()))) {
            return false;
        }
        return MATCHER.match(pattern, path);
    }

    public static boolean anyMatch(List<RoutePolicy> policies, HttpMethod method, String path) {
        return policies.stream().anyMatch(policy -> policy.matches(method, path));
    }
}
