package es.brasatech.fastbite.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Background work, such as sending billing records to the tax agency. */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
