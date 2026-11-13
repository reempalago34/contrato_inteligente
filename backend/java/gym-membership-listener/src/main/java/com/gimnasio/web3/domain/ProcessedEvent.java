package com.gimnasio.web3.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Registro de idempotencia: evita aplicar dos veces el mismo evento si el
 * listener se reinicia (web3j vuelve a leer desde start-block) o si un nodo
 * entrega logs duplicados.
 */
@Entity
@Table(
        name = "processed_events",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_processed_event",
                        columnNames = {"tx_hash", "log_index"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcessedEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tx_hash", nullable = false, length = 66)
    private String txHash;

    @Column(name = "log_index", nullable = false)
    private int logIndex;

    @Column(name = "event_name", nullable = false, length = 64)
    private String eventName;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;
}
