import { artifacts } from "hardhat";
import * as fs from "fs";
import * as path from "path";

/**
 * Exporta el ABI y el bytecode compilados a las carpetas de los backends.
 *
 * Uso:
 *   npx hardhat compile
 *   npx hardhat run scripts/export-abi.ts
 *
 * Genera:
 *   backend/python/abi/GymMembership.json       (ABI, formato web3.py)
 *   backend/python/abi/GymMembership.bin        (bytecode, solo si despliegas desde Python)
 *   backend/java/.../resources/abi/GymMembership.abi
 *   backend/java/.../resources/abi/GymMembership.bin
 */
async function main() {
  const artifact = await artifacts.readArtifact("GymMembership");
  const root = path.resolve(__dirname, "..");

  const targets = [
    {
      dir: path.join(root, "backend", "python", "abi"),
      abiName: "GymMembership.json",
      binName: "GymMembership.bin",
    },
    {
      dir: path.join(
        root,
        "backend",
        "java",
        "gym-membership-listener",
        "src",
        "main",
        "resources",
        "abi"
      ),
      abiName: "GymMembership.abi",
      binName: "GymMembership.bin",
    },
  ];

  for (const target of targets) {
    fs.mkdirSync(target.dir, { recursive: true });
    fs.writeFileSync(
      path.join(target.dir, target.abiName),
      JSON.stringify(artifact.abi, null, 2) + "\n"
    );
    fs.writeFileSync(path.join(target.dir, target.binName), artifact.bytecode);
    console.log(`✅ ABI/bytecode exportados a ${target.dir}`);
  }

  console.log("\nABI (primeras firmas):");
  for (const item of artifact.abi as any[]) {
    if (item.type === "function" || item.type === "event") {
      console.log(`  ${item.type}: ${item.name}`);
    }
  }
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
