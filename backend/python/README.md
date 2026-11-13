# Backend Python — FastAPI + web3.py

Microservicio de ejemplo que valida la membresía NFT **antes** de permitir una
reserva de clase. La lógica on-chain es la fuente de verdad; la base de datos
solo guarda reservas.

## Estructura

```
backend/python/
├── app/
│   ├── config.py      # variables de entorno (pydantic-settings)
│   ├── blockchain.py  # cliente web3.py de solo lectura
│   ├── database.py    # SQLAlchemy (SQLite demo)
│   ├── models.py      # tabla bookings
│   ├── schemas.py     # contratos HTTP (Pydantic)
│   └── main.py        # endpoints FastAPI
├── abi/               # lo genera scripts/export-abi.ts
├── .env.example
└── requirements.txt
```

## Puesta en marcha

```bash
cd backend/python
cp .env.example .env
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt

# 1) En la raíz del repo: levanta Hardhat y despliega
#    npx hardhat node
#    npx hardhat run scripts/deploy.ts --network localhost
#    npx hardhat run scripts/export-abi.ts

# 2) Ajusta CONTRACT_ADDRESS en .env con la dirección impresa por deploy.ts
uvicorn app.main:app --reload --port 8000
```

Documentación interactiva: <http://127.0.0.1:8000/docs>

## Endpoints

| Método | Ruta | Descripción |
| --- | --- | --- |
| GET | `/health` | Estado del RPC |
| GET | `/memberships/{token_id}` | Dueño, expiración y validez |
| POST | `/classes/{class_id}/book` | Reserva validando el NFT |

Ejemplo de reserva:

```bash
curl -X POST http://127.0.0.1:8000/classes/yoga-19h/book \
  -H "Content-Type: application/json" \
  -d '{
    "token_id": 1,
    "wallet_address": "0x70997970C51812dc3A010C7d01b50e0d17dc79C8"
  }'
```

Respuestas esperadas:

- `201` reserva creada.
- `403` el NFT no pertenece a la wallet o la membresía expiró.
- `404` el token no existe.
- `409` ya existe una reserva para esa clase/wallet.
- `503` el nodo RPC no está disponible.

## Notas de producción

- En producción usa PostgreSQL/MySQL (`DATABASE_URL=postgresql+psycopg://...`)
  y migraciones Alembic.
- El endpoint de demo confía en `wallet_address`; en producción exige una
  **firma EIP-4361/EIP-712** de la wallet y verifícala antes de reservar.
- Usa un nodo con reintentos y, si expones el servicio, rate limiting.
- `getMembership()` hace una sola llamada `eth_call` (dueño + expiración +
  validez), reduciendo latencia y carga del nodo.
