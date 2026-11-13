package com.gimnasio.web3.service;

import com.gimnasio.web3.contract.GymMembershipContract;
import io.reactivex.Flowable;
import io.reactivex.disposables.CompositeDisposable;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.DefaultBlockParameterNumber;

import java.math.BigInteger;
import java.util.concurrent.TimeUnit;

/**
 * Event Listener de web3j.
 *
 * Se suscribe a MembershipMinted y MembershipRenewed desde el bloque de
 * despliegue. web3j crea un filtro y hace polling; si el stream falla,
 * `retryWhen` reintenta cada 5 segundos.
 *
 * Producción:
 *  - Usa WebSocketService para latencia casi en tiempo real.
 *  - Persiste el último bloque procesado y define una profundidad de
 *    confirmaciones para reducir el riesgo de reorgs.
 *  - La tabla processed_events hace que los replays sean seguros.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MembershipEventListener {

    private static final long RETRY_DELAY_SECONDS = 5L;

    private final GymMembershipContract contract;
    private final MemberActivationService activationService;

    private final CompositeDisposable subscriptions = new CompositeDisposable();

    @Value("${gym.web3.start-block:0}")
    private long startBlock;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        DefaultBlockParameter from =
                new DefaultBlockParameterNumber(BigInteger.valueOf(startBlock));
        DefaultBlockParameter to = DefaultBlockParameterName.LATEST;

        subscriptions.add(
                contract.membershipMintedEventFlowable(from, to)
                        .retryWhen(this::logAndRetry)
                        .subscribe(
                                activationService::activateFromMint,
                                error ->
                                        log.error(
                                                "Stream MembershipMinted terminado", error)));

        subscriptions.add(
                contract.membershipRenewedEventFlowable(from, to)
                        .retryWhen(this::logAndRetry)
                        .subscribe(
                                activationService::activateFromRenewal,
                                error ->
                                        log.error(
                                                "Stream MembershipRenewed terminado", error)));

        log.info(
                "Escuchando MembershipMinted/MembershipRenewed desde el bloque {} (contrato {})",
                startBlock,
                contract.getContractAddress());
    }

    private Flowable<Long> logAndRetry(Flowable<Throwable> errors) {
        return errors.flatMap(
                error -> {
                    log.warn(
                            "Error en el stream de eventos; reintentando en {}s",
                            RETRY_DELAY_SECONDS,
                            error);
                    return Flowable.timer(RETRY_DELAY_SECONDS, TimeUnit.SECONDS);
                });
    }

    @PreDestroy
    public void stop() {
        subscriptions.clear();
        log.info("Listener de membresías detenido");
    }
}
