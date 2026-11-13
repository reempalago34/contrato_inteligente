# Gimnasio Web3 — Membresías NFT (ERC-721)

Capa Web3 para el sistema de gestión del gimnasio. La membresía deja de ser un
estado de pago en la base de datos y pasa a ser un **NFT de utilidad** que
controla el acceso durante 30 días. El backend solo consulta la blockchain
para validar y escucha eventos para mantener su proyección local.

```
┌────────────────────┐        compra/renueva        ┌──────────────────────────┐
│  Frontend / socio  │ ───────────────────────────► │  GymMembership.sol        │
│  (wallet EVM)      │                              │  ERC-721 + Ownable +      │
└────────────────────┘                              │  ReentrancyGuard          │
          │                                         └──────────┬───────────────┘
          │ reserva de clase                                   │ eventos
          ▼                                                    ▼
┌────────────────────────────┐                    ┌──────────────────────────────┐
│ Python / FastAPI           │  eth_call          │ Java / Spring Boot (web3j)   │
│ isMembershipValid()        │ ─────────────────► │ MembershipMinted/Renewed     │
│ + reservas (SQLite demo)   │                    │ + socios (PostgreSQL/MySQL)  │
└────────────────────────────┘                    └──────────────────────────────┘
```

## Estructura del repositorio

```
contrato_inteligente/
├── contracts/GymMembership.sol          # contrato principal
├── scripts/deploy.ts                    # despliegue local/testnet
├── scripts/export-abi.ts                # ABI + bytecode para los backends
├── test/GymMembership.test.ts           # suite Hardhat
├── hardhat.config.ts
├── package.json
├── backend/
│   ├── python/                          # FastAPI + web3.py (validación de reservas)
│   └── java/gym-membership-listener/    # Spring Boot + web3j (event listener + JPA)
└── deployments/                         # generado por deploy.ts
```

## Requisitos

- Node.js 20+ y npm.
- Python 3.11+.
- JDK 21 y Maven 3.9+.
- PostgreSQL 14+ o MySQL 8+ (para el listener Java).

---

## 1. Smart Contract

`contracts/GymMembership.sol`:

- `ERC721` + `Ownable` + `ReentrancyGuard` de OpenZeppelin v5.
- Precio configurable en moneda nativa; duración fija de 30 días.
- Una membresía por NFT: el `tokenId` es el identificador del socio.
- `_safeMint` + estado escrito antes del mint + `nonReentrant`.
- Sin `ERC721Enumerable` para ahorrar gas; el backend indexa eventos.

### API

| Función | Tipo | Descripción |
| --- | --- | --- |
| `buyMembership()` | payable | Paga el precio y recibe un NFT de 30 días. |
| `renewMembership(tokenId)` | payable | Extiende 30 días (desde la expiración o desde ahora si ya venció). |
| `isMembershipValid(tokenId)` | view | `true` si el token existe y no expiró. |
| `getMembership(tokenId)` | view | Devuelve `(owner, expiresAt, valid)` en una sola llamada. |
| `withdrawFunds()` | onlyOwner | Retira todo el balance al owner (checks-effects-interactions). |
| `setMembershipPrice(newPrice)` | onlyOwner | Actualiza el precio. |
| `setBaseURI(newBaseURI)` | onlyOwner | Actualiza la base de metadatos. |
| `tokenURI(tokenId)` | view | `{baseURI}{tokenId}.json`. |

### Eventos (para el backend)

| Evento | Campos |
| --- | --- |
| `MembershipMinted` | `member indexed`, `tokenId indexed`, `expiresAt`, `amountPaid` |
| `MembershipRenewed` | `member indexed`, `tokenId indexed`, `previousExpiry`, `newExpiry`, `amountPaid` |
| `FundsWithdrawn` | `to indexed`, `amount` |
| `MembershipPriceUpdated` | `previousPrice`, `newPrice` |

> Las membresías son transferibles (ERC-721 estándar). Si quieres que sean
> **soulbound**, sobrescribe `_update` y revierte cuando `from != address(0)`.
> En ese caso, el backend sigue usando `MembershipMinted` como fuente de verdad.

---

## 2. Hardhat: compilar, testear y desplegar

```bash
# Instalar dependencias
npm install

# Compilar
npx hardhat compile

# Tests (incluye expiración, renovación, reentrancy y withdraw)
npx hardhat test

# Con reporte de gas
REPORT_GAS=true npx hardhat test
```

Despliegue en red local:

```bash
# Terminal 1: nodo local con cuentas de prueba
npx hardhat node

# Terminal 2: desplegar
cp .env.example .env
npx hardhat run scripts/deploy.ts --network localhost

# Exportar ABI/bytecode a los backends
npx hardhat run scripts/export-abi.ts
```

`deploy.ts` imprime y guarda en `deployments/<red>.json`:

```json
{
  "contract": "GymMembership",
  "network": "localhost",
  "chainId": 31337,
  "address": "0x5FbDB2315678afecb367f032d93F642f64180aa3",
  "startBlock": 1,
  "membershipPriceWei": "10000000000000000",
  "membershipPriceEth": "0.01",
  "baseURI": "ipfs://REPLACE_WITH_CID/"
}
```

Copia `address` y `startBlock` a los `.env` de Python/Java.

### Compilar y obtener ABI + bytecode (detalle)

Hardhat deja el artefacto completo en:

```
artifacts/contracts/GymMembership.sol/GymMembership.json
```

Dentro de ese JSON:

- `abi` → interfaz para `web3.py`, `web3j`, ethers, viem, etc.
- `bytecode` → código de despliegue (solo necesario si despliegas desde otro
  lenguaje; nuestro despliegue lo hace Hardhat).

El script `scripts/export-abi.ts` los copia a:

```
backend/python/abi/GymMembership.json   # ABI (array)
backend/python/abi/GymMembership.bin    # bytecode
backend/java/.../resources/abi/GymMembership.abi
backend/java/.../resources/abi/GymMembership.bin
```

Alternativa con `solc` directo (si no quieres Hardhat):

```bash
solc --abi --bin --optimize --optimize-runs 200 \
  --include-path node_modules \
  contracts/GymMembership.sol -o build/
```

---

## 3. Integración Python (FastAPI + web3.py)

Endpoint que **valida la membresía antes de reservar**:

```bash
cd backend/python
cp .env.example .env
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
uvicorn app.main:app --reload --port 8000
```

El código relevante está en `app/main.py`:

```python
info = gw.get_membership(payload.token_id)          # 1 eth_call
if info is None:                                    # token inexistente
    raise HTTPException(404, "Membresía no encontrada")
if info.owner.lower() != payload.wallet_address.lower():
    raise HTTPException(403, "La membresía no pertenece a esta wallet")
if not info.is_valid:                               # expiró
    raise HTTPException(403, "Membresía expirada; renueva antes de reservar")
# ... insertar reserva ...
```

`app/blockchain.py` encapsula web3.py:

```python
def is_membership_valid(self, token_id: int) -> bool:
    return bool(self._contract.functions.isMembershipValid(token_id).call())

def get_membership(self, token_id: int) -> MembershipInfo | None:
    member, expiry, valid = self._contract.functions.getMembership(token_id).call()
    if int(member, 16) == 0:
        return None
    return MembershipInfo(
        token_id=token_id,
        owner=Web3.to_checksum_address(member),
        expires_at=datetime.fromtimestamp(int(expiry), tz=timezone.utc),
        is_valid=bool(valid),
    )
```

Prueba rápida:

```bash
curl http://127.0.0.1:8000/memberships/1

curl -X POST http://127.0.0.1:8000/classes/yoga-19h/book \
  -H "Content-Type: application/json" \
  -d '{"token_id":1,"wallet_address":"0x70997970C51812dc3A010C7d01b50e0d17dc79C8"}'
```

> En producción, además de comparar la wallet, exige una firma EIP-712/EIP-4361
> para demostrar que quien reserva controla esa dirección.

---

## 4. Integración Java (Spring Boot + web3j)

Listener de `MembershipMinted` y `MembershipRenewed` que actualiza
`members.status = ACTIVE` y `members.membership_expires_at` en PostgreSQL/MySQL.

```bash
cd backend/java/gym-membership-listener
export CONTRACT_ADDRESS=0x...      # salida de deploy.ts
export WEB3_START_BLOCK=1          # salida de deploy.ts
mvn spring-boot:run                # PostgreSQL por defecto
# o: mvn spring-boot:run -Dspring-boot.run.profiles=mysql
```

Núcleo del listener (`MembershipEventListener.java`):

```java
DefaultBlockParameter from = new DefaultBlockParameterNumber(BigInteger.valueOf(startBlock));

subscriptions.add(
    contract.membershipMintedEventFlowable(from, DefaultBlockParameterName.LATEST)
        .retryWhen(this::logAndRetry)
        .subscribe(activationService::activateFromMint, this::logStreamError));
```

Y la actualización transaccional (`MemberActivationService.java`):

```java
String wallet = event.member.toLowerCase(Locale.ROOT);
Member member = memberRepository.findByWalletAddress(wallet)
        .orElseGet(() -> Member.builder().walletAddress(wallet).build());
member.activate(event.tokenId, Instant.ofEpochSecond(event.expiresAt.longValueExact()));
memberRepository.save(member);
```

Garantías incluidas:

- **Idempotencia**: tabla `processed_events` con `UNIQUE(tx_hash, log_index)`.
- **Reintentos**: `retryWhen` cada 5 s si el stream RPC falla.
- **Servicio de solo lectura**: no necesita private key.
- **Job de expiración**: marca `EXPIRED` cada minuto; la validación real sigue
  siendo `isMembershipValid()` on-chain.

---

## 5. Flujo end-to-end

1. El socio llama `buyMembership()` desde su wallet con `membershipPrice` ETH.
2. El contrato emite `MembershipMinted` y `expiresAt = now + 30 days`.
3. Java escucha el evento y crea/actualiza el socio como `ACTIVE` en la DB.
4. El socio pide reservar una clase al API Python.
5. Python llama `getMembership(tokenId)`; si `valid == false`, responde 403.
6. Si es válido, guarda la reserva. La blockchain es la fuente de verdad.
7. A los 30 días, `isMembershipValid()` devuelve `false` y el job Java marca
   `EXPIRED`. Para volver a reservar, el socio llama `renewMembership(tokenId)`.

---

## 6. Optimización de gas aplicada

| Decisión | Ahorro / motivo |
| --- | --- |
| Custom errors en vez de strings | Evita strings en bytecode; revert barato. |
| Sin `ERC721Enumerable` | Mint/transfer mucho más baratos; el backend indexa. |
| `mapping(uint256 => uint256)` | Un slot por token, sin structs ni arrays. |
| Variables de storage cacheadas | Menos `SLOAD` en `buy`/`renew`/`withdraw`. |
| `unchecked { ++totalMinted; }` | Contador acotado por la práctica. |
| `tokenURI` calculado | Sin `ERC721URIStorage` (un `SSTORE` extra por mint). |
| `_safeMint` solo cuando aporta | Seguridad para wallets contrato; se puede cambiar a `_mint` si todos son EOAs. |
| Optimizer `runs: 200` | Equilibrio entre despliegue y uso diario. |

No se añadió batch mint: cada membresía es un NFT independiente y el volumen
esperado no justifica la complejidad.

---

## 7. Seguridad y producción

- **Reentrancy**: `buyMembership`, `renewMembership` y `withdrawFunds` usan
  `nonReentrant`; el estado se escribe antes de cualquier llamada externa.
- **Acceso**: `withdrawFunds`, `setMembershipPrice` y `setBaseURI` son
  `onlyOwner`.
- **Pago exacto**: se rechaza cualquier `msg.value != membershipPrice`.
- **Reorgs**: el listener de demo no espera confirmaciones; en producción
  define `confirmations`, persiste el último bloque procesado y usa WebSocket.
- **Transferencias**: al ser ERC-721, el NFT puede venderse; la membresía viaja
  con el token. Si no quieres mercado secundario, hazlo soulbound.
- **Metadatos**: `baseURI` debe apuntar a IPFS/HTTPS con el JSON de cada token.
- **Claves**: el listener no necesita private key. Nunca subas `.env` ni claves
  al repositorio.
- **Auditoría**: antes de mainnet, audita el contrato y prueba en Sepolia.

---

## 8. Cómo integrarlo en el repositorio `gimnasio`

Dos opciones:

1. **Monorepo**: copia `contracts/`, `scripts/`, `hardhat.config.ts`,
   `package.json` y `test/` dentro de `gimnasio/web3/`. El backend Java puede
   añadirse como módulo Maven; el servicio Python como carpeta `services/web3/`.
2. **Repositorio separado** (recomendado para el contrato): mantén este repo
   como fuente de verdad del contrato y consume su ABI desde los backends. El
   `deployments/<red>.json` se versiona o se publica como artefacto.

En ambos casos, el contrato y sus despliegues deben tener su propio pipeline
(compilar, testear, desplegar) independiente del backend del gimnasio.

---

## Comandos útiles

```bash
npx hardhat compile
npx hardhat test
npx hardhat node
npx hardhat run scripts/deploy.ts --network localhost
npx hardhat run scripts/export-abi.ts
npx hardhat run scripts/deploy.ts --network sepolia   # requiere .env
```
