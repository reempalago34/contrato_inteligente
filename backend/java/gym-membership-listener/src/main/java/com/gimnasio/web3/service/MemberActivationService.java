package com.gimnasio.web3.service;

import com.gimnasio.web3.contract.GymMembershipContract.MembershipMintedEventResponse;
import com.gimnasio.web3.contract.GymMembershipContract.MembershipRenewedEventResponse;
import com.gimnasio.web3.domain.Member;
import com.gimnasio.web3.domain.MemberStatus;
import com.gimnasio.web3.domain.ProcessedEvent;
import com.gimnasio.web3.repository.MemberRepository;
import com.gimnasio.web3.repository.ProcessedEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.web3j.protocol.core.methods.response.Log;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Locale;

/**
 * Sincroniza la base de datos con los eventos on-chain.
 *
 * - MembershipMinted  -> crea/actualiza el socio y lo marca ACTIVE.
 * - MembershipRenewed -> extiende membershipExpiresAt.
 *
 * La tabla processed_events garantiza idempotencia ante reinicios/replays.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MemberActivationService {

    private final MemberRepository memberRepository;
    private final ProcessedEventRepository processedEventRepository;

    @Transactional
    public void activateFromMint(MembershipMintedEventResponse event) {
        if (event.log == null || event.log.isRemoved()) {
            return;
        }
        if (alreadyProcessed(event.log)) {
            log.debug("MembershipMinted ya procesado: tx={}", event.log.getTransactionHash());
            return;
        }

        String wallet = normalize(event.member);
        Member member = findOrCreate(wallet);
        member.activate(event.tokenId, toInstant(event.expiresAt));
        memberRepository.save(member);

        markProcessed(event.log, "MembershipMinted");

        log.info(
                "MembershipMinted: wallet={} tokenId={} expira={} amountWei={}",
                wallet,
                event.tokenId,
                member.getMembershipExpiresAt(),
                event.amountPaid);
    }

    @Transactional
    public void activateFromRenewal(MembershipRenewedEventResponse event) {
        if (event.log == null || event.log.isRemoved()) {
            return;
        }
        if (alreadyProcessed(event.log)) {
            log.debug("MembershipRenewed ya procesado: tx={}", event.log.getTransactionHash());
            return;
        }

        String wallet = normalize(event.member);
        Member member = findOrCreate(wallet);
        member.renew(event.tokenId, toInstant(event.newExpiry));
        memberRepository.save(member);

        markProcessed(event.log, "MembershipRenewed");

        log.info(
                "MembershipRenewed: wallet={} tokenId={} nuevaExpiracion={}",
                wallet,
                event.tokenId,
                member.getMembershipExpiresAt());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------
    private Member findOrCreate(String wallet) {
        return memberRepository
                .findByWalletAddress(wallet)
                .orElseGet(
                        () ->
                                Member.builder()
                                        .walletAddress(wallet)
                                        .status(MemberStatus.PENDING)
                                        .build());
    }

    private boolean alreadyProcessed(Log log) {
        int logIndex = logIndex(log);
        return processedEventRepository.existsByTxHashAndLogIndex(
                log.getTransactionHash(), logIndex);
    }

    private void markProcessed(Log log, String eventName) {
        processedEventRepository.save(
                ProcessedEvent.builder()
                        .txHash(log.getTransactionHash())
                        .logIndex(logIndex(log))
                        .eventName(eventName)
                        .processedAt(Instant.now())
                        .build());
    }

    private static int logIndex(Log log) {
        BigInteger index = log.getLogIndex();
        return index == null ? -1 : index.intValueExact();
    }

    private static Instant toInstant(BigInteger epochSeconds) {
        return Instant.ofEpochSecond(epochSeconds.longValueExact());
    }

    private static String normalize(String address) {
        return address == null ? "" : address.toLowerCase(Locale.ROOT);
    }
}
