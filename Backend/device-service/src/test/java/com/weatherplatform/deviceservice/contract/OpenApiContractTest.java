package com.weatherplatform.deviceservice.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps the committed OpenAPI document honest.
 *
 * The static specification and the controllers are two descriptions of the same
 * contract, and nothing but this test stops them drifting apart. A stale
 * specification is not a harmless documentation problem: this service's
 * document once described `POST /api/devices/register` and
 * `PUT /api/devices/status/{deviceUid}`, neither of which ever existed at those
 * paths, while omitting commands, provisioning, and firmware entirely.
 *
 * Deliberately a plain unit test: it reads annotations off the compiled
 * controllers, so it needs no Spring context, no database, and no container.
 */
class OpenApiContractTest {

    private static final String BASE_PACKAGE = "com.weatherplatform.deviceservice";
    private static final String SPEC_LOCATION = "openapi/device-service-api.yaml";

    /**
     * Paths served by a controller but intentionally not given their own entry
     * in the specification. Each one must be a documented alias of a path that
     * *is* in the specification — never a way to hide a new endpoint.
     */
    private static final Set<String> DOCUMENTED_ALIASES = Set.of();

    @Test
    @DisplayName("every path in the specification is actually served by a controller")
    void specificationDescribesNoRemovedEndpoints() {
        Set<String> removed = new TreeSet<>(documentedPaths());
        removed.removeAll(servedPaths());

        assertThat(removed)
                .as("%s documents endpoints this service no longer serves. Remove them from the "
                        + "specification, or restore the endpoint if its removal was a mistake.", SPEC_LOCATION)
                .isEmpty();
    }

    @Test
    @DisplayName("every path a controller serves appears in the specification")
    void specificationDocumentsEveryEndpoint() {
        Set<String> undocumented = new TreeSet<>(servedPaths());
        undocumented.removeAll(documentedPaths());
        undocumented.removeAll(DOCUMENTED_ALIASES);

        assertThat(undocumented)
                .as("These endpoints are served but missing from %s. Document them, or add the path to "
                        + "DOCUMENTED_ALIASES if it is an alias of an already documented operation.", SPEC_LOCATION)
                .isEmpty();
    }

    @SuppressWarnings("unchecked")
    private Set<String> documentedPaths() {
        try (InputStream spec = new ClassPathResource(SPEC_LOCATION).getInputStream()) {
            Map<String, Object> root = new Yaml().load(spec);
            Map<String, Object> paths = (Map<String, Object>) root.get("paths");
            return paths == null ? Set.of() : new TreeSet<>(paths.keySet());
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + SPEC_LOCATION, e);
        }
    }

    /** Every request path declared by an {@code @RestController} in this service. */
    private Set<String> servedPaths() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        Set<String> paths = new LinkedHashSet<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(BASE_PACKAGE)) {
            Class<?> controller = resolve(definition.getBeanClassName());
            for (String prefix : mappingValues(AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class))) {
                for (Method method : controller.getDeclaredMethods()) {
                    RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                    if (mapping == null) {
                        continue;
                    }
                    for (String suffix : mappingValues(mapping)) {
                        paths.add(join(prefix, suffix));
                    }
                }
            }
        }
        return new TreeSet<>(paths);
    }

    /** A mapping with no explicit path still maps its prefix, so treat that as one empty segment. */
    private Set<String> mappingValues(RequestMapping mapping) {
        if (mapping == null || mapping.path().length == 0) {
            return Collections.singleton("");
        }
        return new LinkedHashSet<>(Set.of(mapping.path()));
    }

    private String join(String prefix, String suffix) {
        String joined = (prefix + suffix).replaceAll("/{2,}", "/");
        return joined.length() > 1 && joined.endsWith("/") ? joined.substring(0, joined.length() - 1) : joined;
    }

    private Class<?> resolve(String className) {
        try {
            return ClassUtils.forName(className, getClass().getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Scanned controller could not be loaded: " + className, e);
        }
    }
}
