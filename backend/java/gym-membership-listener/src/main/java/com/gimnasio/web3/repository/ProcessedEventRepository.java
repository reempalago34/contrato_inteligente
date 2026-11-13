package com.gimnasio.web3.repository;

import com.gimnasio.web3.domain.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, Long> {

    boolean existsByTxHashAndLogIndex(String txHash, int logIndex);
}
