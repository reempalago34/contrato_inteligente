package com.gimnasio.web3.service;

import com.gimnasio.web3.domain.MemberStatus;
import com.gimnasio.web3.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Job de higiene: marca EXPIRED a los socios cuya fecha on-chain ya pasó.
 * No sustituye la validación en tiempo real (siempre se consulta el contrato
 * antes de una reserva), pero mantiene consistente el resto del sistema.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MembershipExpiryJob {

    private final MemberRepository memberRepository;

    @Scheduled(fixedDelayString = "${gym.membership.expiry-check-ms:60000}")
    @Transactional
    public void expireMemberships() {
        int updated =
                memberRepository.expireMemberships(
                        MemberStatus.ACTIVE, MemberStatus.EXPIRED, Instant.now());

        if (updated > 0) {
            log.info("Membresías marcadas como EXPIRED: {}", updated);
        }
    }
}
