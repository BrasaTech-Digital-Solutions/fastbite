package es.brasatech.fastbite;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApplicationTimeZoneTest {

    private TimeZone originalTimeZone;

    @BeforeEach
    void setUp() {
        originalTimeZone = TimeZone.getDefault();
    }

    @AfterEach
    void tearDown() {
        TimeZone.setDefault(originalTimeZone);
    }

    @Test
    void defaultTimeZoneIsMadridWhenNullOrBlank() {
        Application.configureDefaultTimeZone((String) null);
        assertThat(TimeZone.getDefault().toZoneId()).isEqualTo(ZoneId.of("Europe/Madrid"));

        Application.configureDefaultTimeZone("   ");
        assertThat(TimeZone.getDefault().toZoneId()).isEqualTo(ZoneId.of("Europe/Madrid"));
    }

    @Test
    void appliesConfiguredValidTimeZone() {
        Application.configureDefaultTimeZone("America/New_York");
        assertThat(TimeZone.getDefault().toZoneId()).isEqualTo(ZoneId.of("America/New_York"));

        Application.configureDefaultTimeZone("UTC");
        assertThat(TimeZone.getDefault().toZoneId()).isEqualTo(ZoneId.of("UTC"));
    }

    @Test
    void rejectsInvalidTimeZone() {
        assertThatThrownBy(() -> Application.configureDefaultTimeZone("Invalid/Time_Zone"))
                .isInstanceOf(DateTimeException.class);
    }

    @Test
    void defaultZoneConstantMatchesFiscalConfig() {
        assertThat(Application.DEFAULT_TIME_ZONE).isEqualTo("Europe/Madrid");
    }
}
