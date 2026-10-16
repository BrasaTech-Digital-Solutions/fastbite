package es.brasatech.fastbite;

import es.brasatech.fastbite.application.office.I18nConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ImportRuntimeHints;

@SpringBootApplication(scanBasePackages = {"es.brasatech.fastbite"})
@ImportRuntimeHints({es.brasatech.fastbite.config.WebAdapterHints.class, es.brasatech.fastbite.config.StripeHints.class})
public class Application {

    public static void main(String[] args) {
        if (java.util.Arrays.asList(args).contains("--migration-job")) {
            System.exit(MigrationJob.run(args));
            return;
        }
        SpringApplication.run(Application.class, args);
    }

    @Bean
    @ConfigurationProperties(prefix = "i18n")
    public I18nConfig i18nConfig() {
        return new I18nConfig();
    }

}
