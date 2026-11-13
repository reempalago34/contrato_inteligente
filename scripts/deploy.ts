import { ethers, network } from "hardhat";
import * as fs from "fs";
import * as path from "path";

/**
 * Despliegue de GymMembership.
 *
 * Uso:
 *   Terminal 1: npx hardhat node
 *   Terminal 2: npx hardhat run scripts/deploy.ts --network localhost
 *
 * El script guarda la dirección y el bloque inicial en:
 *   deployments/<red>.json  y  deployments/latest.json
 * para que los backends (Python/Java) sepan desde qué bloque escuchar.
 */
async function main() {
  const [deployer] = await ethers.getSigners();

  const priceEth = process.env.MEMBERSHIP_PRICE_ETH ?? "0.01";
  const baseURI = process.env.MEMBERSHIP_BASE_URI ?? "ipfs://REPLACE_ME/";
  const membershipPrice = ethers.parseEther(priceEth);

  if (membershipPrice === 0n) {
    throw new Error("MEMBERSHIP_PRICE_ETH debe ser mayor que 0");
  }

  const net = await ethers.provider.getNetwork();
  console.log("──────────────────────────────────────────────");
  console.log(`Red:        ${network.name} (chainId ${net.chainId})`);
  console.log(`Deployer:   ${deployer.address}`);
  console.log(
    `Balance:    ${ethers.formatEther(
      await ethers.provider.getBalance(deployer.address)
    )} ETH`
  );
  console.log(`Precio:     ${priceEth} ETH`);
  console.log(`Base URI:   ${baseURI}`);
  console.log("──────────────────────────────────────────────");

  const factory = await ethers.getContractFactory("GymMembership");
  const contract = await factory.deploy(membershipPrice, baseURI);
  await contract.waitForDeployment();

  const address = await contract.getAddress();
  const txHash = contract.deploymentTransaction()?.hash ?? "";
  const startBlock = await ethers.provider.getBlockNumber();

  console.log(`✅ GymMembership desplegado en: ${address}`);
  console.log(`   tx: ${txHash}`);
  console.log(`   bloque inicial para listeners: ${startBlock}`);

  const deployment = {
    contract: "GymMembership",
    network: network.name,
    chainId: Number(net.chainId),
    address,
    startBlock,
    transactionHash: txHash,
    deployer: deployer.address,
    membershipPriceWei: membershipPrice.toString(),
    membershipPriceEth: priceEth,
    baseURI,
    deployedAt: new Date().toISOString(),
  };

  const deploymentsDir = path.join(__dirname, "..", "deployments");
  fs.mkdirSync(deploymentsDir, { recursive: true });
  fs.writeFileSync(
    path.join(deploymentsDir, `${network.name}.json`),
    JSON.stringify(deployment, null, 2) + "\n"
  );
  fs.writeFileSync(
    path.join(deploymentsDir, "latest.json"),
    JSON.stringify(deployment, null, 2) + "\n"
  );

  console.log("\nCopia estas variables en los .env de los backends:");
  console.log(`CONTRACT_ADDRESS=${address}`);
  console.log(`START_BLOCK=${startBlock}`);
  console.log(`CHAIN_ID=${net.chainId}`);
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
