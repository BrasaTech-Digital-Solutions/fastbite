package es.brasatech.fastbite.application.digitalsign;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers {@link DigitalSignProperties} for binding and AOT processing. */
@Configuration
@EnableConfigurationProperties(DigitalSignProperties.class)
public class DigitalSignConfiguration {
}
