package com.gimnasio.web3;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Servicio que escucha los eventos del contrato GymMembership y sincroniza
 * el estado de los socios en la base de datos relacional.
 *
 * Ejecutar: mvn spring-boot:run
 * Perfil MySQL: mvn spring-boot:run -Dspring-boot.run.profiles=mysql
 */
@SpringBootApplication
@EnableScheduling
public class GymMembershipListenerApplication {

    public static void main(String[] args) {
        SpringApplication.run(GymMembershipListenerApplication.class, args);
    }
}
