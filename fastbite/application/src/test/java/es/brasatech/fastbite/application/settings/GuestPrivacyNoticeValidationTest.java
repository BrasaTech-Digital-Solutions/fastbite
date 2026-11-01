package es.brasatech.fastbite.application.settings;

import es.brasatech.fastbite.domain.exception.LocalizedRejection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GuestPrivacyNoticeValidationTest {
    @Test void eachInvalidPdfHasItsOwnActionableRejection() {
        assertEquals("guest.notice.error.choose", assertThrows(LocalizedRejection.class,
                () -> GuestPrivacyNoticeService.validatePdf(new byte[0])).key());
        assertEquals("guest.notice.error.size", assertThrows(LocalizedRejection.class,
                () -> GuestPrivacyNoticeService.validatePdf(new byte[GuestPrivacyNoticeService.MAX_PDF_BYTES + 1])).key());
        assertEquals("guest.notice.error.notPdf", assertThrows(LocalizedRejection.class,
                () -> GuestPrivacyNoticeService.validatePdf("not a PDF".getBytes())).key());
    }
}
