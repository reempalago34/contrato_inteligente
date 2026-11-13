# ABI generado

Esta carpeta se llena automáticamente con:

```bash
npx hardhat run scripts/export-abi.ts
```

Genera:

- `GymMembership.abi` — ABI JSON (para `web3j generate solidity`).
- `GymMembership.bin` — bytecode (solo si despliegas desde Java).

El listener incluido **no** necesita el ABI en runtime: el wrapper
`GymMembershipContract` declara los eventos y funciones que usa.
