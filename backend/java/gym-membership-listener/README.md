# Backend Java — Spring Boot + web3j

Listener de eventos que sincroniza el estado del socio en PostgreSQL/MySQL.
Cuando el contrato emite `MembershipMinted`, el socio pasa a `ACTIVE` con su
`tokenId` y fecha de expiración. `MembershipRenewed` extiende la fecha.

## Estructura

```
src/main/java/com/gimnasio/web3/
├── GymMembershipListenerApplication.java
├── config/Web3jConfig.java              # Web3j + wrapper de solo lectura
├── contract/GymMembershipContract.java  # eventos y llamadas de lectura
├── domain/Member.java                   # entidad socios
├── domain/MemberStatus.java
├── domain/ProcessedEvent.java           # idempotencia (txHash + logIndex)
├── repository/MemberRepository.java
├── repository/ProcessedEventRepository.java
├── service/MemberActivationService.java # mint/renew -> DB
├── service/MembershipEventListener.java # suscripción web3j
└── service/MembershipExpiryJob.java     # job que marca EXPIRED
```

## Base de datos

PostgreSQL (por defecto):

```sql
CREATE DATABASE gimnasio;
CREATE USER gimnasio WITH PASSWORD 'gimnasio';
GRANT ALL PRIVILEGES ON DATABASE gimnasio TO gimnasio;
```

MySQL (perfil `mysql`):

```sql
CREATE DATABASE gimnasio CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'gimnasio'@'%' IDENTIFIED BY 'gimnasio';
GRANT ALL PRIVILEGES ON gimnasio.* TO 'gimnasio'@'%';
FLUSH PRIVILEGES;
```

`ddl-auto: update` crea las tablas en local. En producción cambia a `validate`
y usa Flyway/Liquibase.

## Ejecutar

```bash
# 1) Hardhat + despliegue + exportación del ABI (desde la raíz del repo)
npx hardhat node
npx hardhat run scripts/deploy.ts --network localhost
npx hardhat run scripts/export-abi.ts

# 2) Listener
cd backend/java/gym-membership-listener
export CONTRACT_ADDRESS=0x...   # el que imprimió deploy.ts
export WEB3_START_BLOCK=0        # el que imprimió deploy.ts
mvn spring-boot:run
```

Con MySQL:

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=mysql
```

Variables de entorno principales:

| Variable | Default |
| --- | --- |
| `WEB3_RPC_URL` | `http://127.0.0.1:8545` |
| `CONTRACT_ADDRESS` | dirección local de ejemplo |
| `WEB3_START_BLOCK` | `0` |
| `DB_URL` | `jdbc:postgresql://localhost:5432/gimnasio` |
| `DB_USER` / `DB_PASSWORD` | `gimnasio` / `gimnasio` |

## Generar el wrapper oficial (opcional)

El wrapper incluido es mínimo y de solo lectura. Para generar el wrapper
completo con web3j CLI:

```bash
web3j generate solidity \
  -a src/main/resources/abi/GymMembership.abi \
  -b src/main/resources/abi/GymMembership.bin \
  -o src/main/java \
  -p com.gimnasio.web3.generated
```

## Notas de producción

- `web3j.ethLogFlowable` hace polling. Para tiempo real usa `WebSocketService`.
- Persiste el último bloque procesado y define confirmaciones para reorgs.
- `processed_events` ya evita dobles aplicaciones por reinicios/replays.
- El listener es de solo lectura: nunca necesita una private key.
- Si el gimnasio emite membresías desde otro servicio, ese servicio sí firma
  transacciones; mantenlo separado del listener.
