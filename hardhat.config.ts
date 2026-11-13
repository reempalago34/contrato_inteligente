import { HardhatUserConfig } from "hardhat/config";
import "@nomicfoundation/hardhat-toolbox";
import * as dotenv from "dotenv";

dotenv.config();

const SEPOLIA_RPC_URL = process.env.SEPOLIA_RPC_URL ?? "";
const SEPOLIA_PRIVATE_KEY = process.env.SEPOLIA_PRIVATE_KEY ?? "";

const config: HardhatUserConfig = {
  solidity: {
    // OpenZeppelin v5 requiere >=0.8.20. 0.8.24 es estable y barato.
    version: "0.8.24",
    settings: {
      optimizer: {
        enabled: true,
        runs: 200, // equilibrado para funciones que se llaman a diario
      },
      // viaIR reduce bytecode pero alarga la compilación; actívalo si te
      // acercas al límite de 24 KB.
      viaIR: false,
    },
  },
  networks: {
    // Red en memoria para tests: `npx hardhat test`
    hardhat: {
      chainId: 31337,
    },
    // Nodo local: `npx hardhat node`
    localhost: {
      url: "http://127.0.0.1:8545",
      chainId: 31337,
    },
    ...(SEPOLIA_RPC_URL && SEPOLIA_PRIVATE_KEY
      ? {
          sepolia: {
            url: SEPOLIA_RPC_URL,
            accounts: [SEPOLIA_PRIVATE_KEY],
            chainId: 11155111,
          },
        }
      : {}),
  },
  gasReporter: {
    enabled: process.env.REPORT_GAS === "true",
    currency: "USD",
  },
  paths: {
    sources: "./contracts",
    tests: "./test",
    cache: "./cache",
    artifacts: "./artifacts",
  },
};

export default config;
