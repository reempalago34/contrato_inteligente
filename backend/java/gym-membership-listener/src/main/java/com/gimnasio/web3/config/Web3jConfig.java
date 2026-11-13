package com.gimnasio.web3.config;

import com.gimnasio.web3.contract.GymMembershipContract;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;

/**
 * Configuración de web3j.
 *
 * Para producción con menos latencia, cambia HttpService por WebSocketService:
 *   new WebSocketService("ws://127.0.0.1:8546", true)
 * y usa `Web3j.build(webSocketService)`.
 */
@Configuration
public class Web3jConfig {

    @Bean(destroyMethod = "shutdown")
    public Web3j web3j(@Value("${gym.web3.rpc-url}") String rpcUrl) {
        return Web3j.build(new HttpService(rpcUrl));
    }

    @Bean
    public GymMembershipContract gymMembershipContract(
            Web3j web3j,
            @Value("${gym.web3.contract-address}") String contractAddress) {

        if (contractAddress == null || !contractAddress.matches("^0x[0-9a-fA-F]{40}$")) {
            throw new IllegalArgumentException(
                    "gym.web3.contract-address inválida: " + contractAddress);
        }

        return new GymMembershipContract(contractAddress, web3j);
    }
}
