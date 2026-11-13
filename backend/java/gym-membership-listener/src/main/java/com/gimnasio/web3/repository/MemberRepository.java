package com.gimnasio.web3.repository;

import com.gimnasio.web3.domain.Member;
import com.gimnasio.web3.domain.MemberStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface MemberRepository extends JpaRepository<Member, Long> {

    Optional<Member> findByWalletAddress(String walletAddress);

    boolean existsByWalletAddress(String walletAddress);

    /**
     * Marca en bloque las membresías vencidas. Se ejecuta periódicamente para
     * que la base de datos no quede desfasada si nadie renueva.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            """
            update Member m
               set m.status = :expiredStatus,
                   m.updatedAt = :now
             where m.status = :activeStatus
               and m.membershipExpiresAt is not null
               and m.membershipExpiresAt < :now
            """)
    int expireMemberships(
            @Param("activeStatus") MemberStatus activeStatus,
            @Param("expiredStatus") MemberStatus expiredStatus,
            @Param("now") Instant now);
}
