package es.brasatech.fastbite.jpa.legal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** A version row in {@code guest_privacy_notices} within a restaurant schema. */
@Entity(name = "GuestPrivacyNotice")
@Table(name = "guest_privacy_notices")
@Getter
@Setter
public class GuestPrivacyNoticeEntity {

    @Id
    private int version;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "storage_path", nullable = false, length = 512)
    private String storagePath;

    @Column(name = "file_size", nullable = false)
    private long fileSize;

    @Column(name = "sha256", nullable = false, length = 64)
    private String sha256;

    @Column(name = "uploaded_by", nullable = false, length = 100)
    private String uploadedBy;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    @Column(name = "active", nullable = false)
    private boolean active;
}
