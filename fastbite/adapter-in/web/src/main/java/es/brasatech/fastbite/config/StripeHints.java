package es.brasatech.fastbite.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.SimpleMetadataReaderFactory;
import org.springframework.util.ClassUtils;

import java.io.IOException;

/**
 * Registers reflection hints for the Stripe Java SDK (model, param, and net classes).
 * The Stripe SDK uses Gson for serialization/deserialization and reflective parameter building.
 */
public class StripeHints implements RuntimeHintsRegistrar {

    private static final Logger log = LoggerFactory.getLogger(StripeHints.class);

    private static final MemberCategory[] CATEGORIES = new MemberCategory[]{
            MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
            MemberCategory.INVOKE_DECLARED_METHODS,
            MemberCategory.ACCESS_DECLARED_FIELDS,
            MemberCategory.UNSAFE_ALLOCATED
    };

    private static final String[] SCAN_PATTERNS = new String[]{
            "classpath*:com/stripe/model/**/*.class",
            "classpath*:com/stripe/param/**/*.class",
            "classpath*:com/stripe/net/**/*.class"
    };

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver(classLoader);
        SimpleMetadataReaderFactory readerFactory = new SimpleMetadataReaderFactory(resolver);

        int registeredCount = 0;
        for (String pattern : SCAN_PATTERNS) {
            try {
                Resource[] resources = resolver.getResources(pattern);
                for (Resource resource : resources) {
                    try {
                        MetadataReader reader = readerFactory.getMetadataReader(resource);
                        String className = reader.getClassMetadata().getClassName();
                        if (className.endsWith("package-info") || className.endsWith("module-info")) {
                            continue;
                        }
                        try {
                            Class<?> clazz = ClassUtils.forName(className, classLoader);
                            hints.reflection().registerType(clazz, CATEGORIES);
                            registeredCount++;
                        } catch (Throwable t) {
                            log.debug("Skipping unresolvable Stripe class: {}", className, t);
                        }
                    } catch (Throwable t) {
                        log.debug("Could not read metadata for resource: {}", resource, t);
                    }
                }
            } catch (IOException e) {
                log.warn("Failed to scan resources for pattern {}: {}", pattern, e.getMessage());
            }
        }
        log.info("Registered {} Stripe SDK classes for native reflection hints", registeredCount);
    }
}
