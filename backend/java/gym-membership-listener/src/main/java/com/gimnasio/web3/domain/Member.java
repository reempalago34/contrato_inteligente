package com.gimnasio.web3.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigInteger;
import java.time.Instant;

/**
 * Socio del gimnasio. La fuente de verdad de la vigencia es la blockchain;
 * esta tabla es una proyección local para consultas rápidas y para el resto
 * del sistema (reservas, acceso, etc.).
 */
@Entity
@Table(
        name = "members",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_members_wallet",
                        columnNames = "wallet_address"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Member {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Dirección EVM normalizada a minúsculas. */
    @Column(name = "wallet_address", nullable = false, length = 42)
    private String walletAddress;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private MemberStatus status = MemberStatus.PENDING;

    /** tokenId del NFT de membresía. */
    @Column(name = "membership_token_id", precision = 78, scale = 0)
    private BigInteger membershipTokenId;

    /** Expiración on-chain (UTC). */
    @Column(name = "membership_expires_at")
    private Instant membershipExpiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    /** Aplica el estado derivado del evento MembershipMinted. */
    public void activate(BigInteger tokenId, Instant expiresAt) {
        this.membershipTokenId = tokenId;
        this.membershipExpiresAt = expiresAt;
        this.status =
                expiresAt != null && expiresAt.isAfter(Instant.now())
                        ? MemberStatus.ACTIVE
                        : MemberStatus.EXPIRED;
    }

    /** Aplica el estado derivado del evento MembershipRenewed. */
    public void renew(BigInteger tokenId, Instant newExpiry) {
        activate(tokenId, newExpiry);
    }
}
