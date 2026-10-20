package es.brasatech.fastbite.domain.table;

import java.time.LocalDateTime;
import java.util.List;

public record Table(String id, String name, int seats, TableStatus status, boolean active, List<String> orderIds,
                    Boolean qrTrusted, String qrTrustedBy, LocalDateTime qrTrustedAt) {

    public Table(String id, String name, int seats, TableStatus status, boolean active, List<String> orderIds) {
        this(id, name, seats, status, active, orderIds, null, null, null);
    }

    public Table(String name, int seats) {
        this(null, name, seats, TableStatus.AVAILABLE, true, List.of(), null, null, null);
    }

    public boolean effectiveQrTrusted(boolean defaultTrusted) {
        return qrTrusted != null ? qrTrusted : defaultTrusted;
    }
}
