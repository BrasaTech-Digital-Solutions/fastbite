package es.brasatech.fastbite.domain.fiscal;

import java.time.LocalDate;

/**
 * Ticket numbering: each location, kind of ticket and year has its own series, numbered from 1
 * without gaps. Training tickets from the sandbox use separate series that start with TEST.
 */
public final class TicketSeries {

    public static final String TRAINING_PREFIX = "TEST";

    private TicketSeries() {
    }

    /** The series a ticket goes in, such as KHC-T26, or TEST-KHC-T26 for training. */
    public static String of(String locationPrefix, TicketKind kind, LocalDate issuedOn, boolean training) {
        String series = locationPrefix + "-" + kind.seriesLetter() + String.format("%02d", issuedOn.getYear() % 100);
        return training ? TRAINING_PREFIX + "-" + series : series;
    }

    /** A ticket's printed number: its series and its number in it, such as KHC-T26-000123. */
    public static String fullNumber(String series, int number) {
        return series + "-" + String.format("%06d", number);
    }
}
