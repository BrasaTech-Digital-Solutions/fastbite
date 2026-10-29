package es.brasatech.fastbite.jpa.user;

import es.brasatech.fastbite.domain.user.Role;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

@Entity
@Table(name = "users")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(unique = true, nullable = false)
    private String username;

    @Column(nullable = false)
    private String password;

    private String fullName;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "role")
    private Set<Role> roles;

    @Column(length = 254)
    private String email;

    @Column(name = "email_verified_at")
    private Long emailVerifiedAt;

    @Column(name = "pending_email", length = 254)
    private String pendingEmail;

    @Column(name = "account_language", length = 2)
    private String accountLanguage;

    private boolean active;

    @Column(name = "tenant_id")
    private String tenantId;
}
