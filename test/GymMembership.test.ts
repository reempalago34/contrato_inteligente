import { expect } from "chai";
import { ethers } from "hardhat";
import {
  loadFixture,
  time,
} from "@nomicfoundation/hardhat-toolbox/network-helpers";
import { anyValue } from "@nomicfoundation/hardhat-chai-matchers/withArgs";

const PRICE = ethers.parseEther("0.01");
const DURATION = 30n * 24n * 60n * 60n; // 30 días en segundos

describe("GymMembership", () => {
  async function deployFixture() {
    const [owner, alice, bob] = await ethers.getSigners();
    const factory = await ethers.getContractFactory("GymMembership");
    const contract = await factory.deploy(PRICE);
    await contract.waitForDeployment();
    return { contract, owner, alice, bob };
  }

  describe("Despliegue", () => {
    it("guarda el precio inicial", async () => {
      const { contract } = await loadFixture(deployFixture);
      expect(await contract.membershipPrice()).to.equal(PRICE);
      expect(await contract.totalMinted()).to.equal(0n);
    });

    it("rechaza precio inicial 0", async () => {
      const factory = await ethers.getContractFactory("GymMembership");
      await expect(factory.deploy(0n)).to.be.revertedWith("Precio invalido");
    });
  });

  describe("buyMembership", () => {
    it("acuña un NFT válido y emite MembershipMinted", async () => {
      const { contract, alice } = await loadFixture(deployFixture);

      const tx = contract.connect(alice).buyMembership({ value: PRICE });

      await expect(tx)
        .to.emit(contract, "MembershipMinted")
        .withArgs(alice.address, 1n, anyValue, PRICE);

      await expect(tx).to.changeEtherBalances(
        [alice, contract],
        [-PRICE, PRICE]
      );

      expect(await contract.ownerOf(1n)).to.equal(alice.address);
      expect(await contract.totalMinted()).to.equal(1n);
      expect(await contract.isMembershipValid(1n)).to.be.true;

      const expiry = await contract.expiresAt(1n);
      const now = BigInt(await time.latest());
      expect(expiry).to.be.greaterThan(now);
      expect(expiry).to.be.lessThanOrEqual(now + DURATION + 1n);
    });

    it("incrementa el tokenId en cada compra", async () => {
      const { contract, alice, bob } = await loadFixture(deployFixture);

      await contract.connect(alice).buyMembership({ value: PRICE });
      await contract.connect(bob).buyMembership({ value: PRICE });

      expect(await contract.ownerOf(1n)).to.equal(alice.address);
      expect(await contract.ownerOf(2n)).to.equal(bob.address);
      expect(await contract.totalMinted()).to.equal(2n);
    });

    it("revierte si el pago es incorrecto", async () => {
      const { contract, alice } = await loadFixture(deployFixture);

      await expect(
        contract.connect(alice).buyMembership({ value: PRICE - 1n })
      ).to.be.revertedWith("Pago incorrecto");

      await expect(
        contract.connect(alice).buyMembership({ value: PRICE + 1n })
      ).to.be.revertedWith("Pago incorrecto");
    });
  });

  describe("renewMembership", () => {
    it("extiende 30 días desde la expiración actual", async () => {
      const { contract, alice } = await loadFixture(deployFixture);
      await contract.connect(alice).buyMembership({ value: PRICE });

      const previousExpiry = await contract.expiresAt(1n);

      await expect(
        contract.connect(alice).renewMembership(1n, { value: PRICE })
      )
        .to.emit(contract, "MembershipRenewed")
        .withArgs(
          alice.address,
          1n,
          previousExpiry,
          previousExpiry + DURATION,
          PRICE
        );

      expect(await contract.expiresAt(1n)).to.equal(
        previousExpiry + DURATION
      );
    });

    it("si expiró, cuenta desde block.timestamp", async () => {
      const { contract, alice } = await loadFixture(deployFixture);
      await contract.connect(alice).buyMembership({ value: PRICE });

      await time.increase(Number(DURATION) + 100);
      expect(await contract.isMembershipValid(1n)).to.be.false;

      await contract.connect(alice).renewMembership(1n, { value: PRICE });

      const newExpiry = await contract.expiresAt(1n);
      const now = BigInt(await time.latest());
      expect(newExpiry).to.equal(now + DURATION);
      expect(await contract.isMembershipValid(1n)).to.be.true;
    });

    it("un tercero no autorizado no puede renovar", async () => {
      const { contract, alice, bob } = await loadFixture(deployFixture);
      await contract.connect(alice).buyMembership({ value: PRICE });

      await expect(
        contract.connect(bob).renewMembership(1n, { value: PRICE })
      ).to.be.revertedWith("No autorizado");
    });

    it("un operador aprobado sí puede renovar", async () => {
      const { contract, alice, bob } = await loadFixture(deployFixture);
      await contract.connect(alice).buyMembership({ value: PRICE });
      await contract.connect(alice).approve(bob.address, 1n);

      await expect(
        contract.connect(bob).renewMembership(1n, { value: PRICE })
      )
        .to.emit(contract, "MembershipRenewed")
        .withArgs(alice.address, 1n, anyValue, anyValue, PRICE);
    });

    it("revierte con pago incorrecto", async () => {
      const { contract, alice } = await loadFixture(deployFixture);
      await contract.connect(alice).buyMembership({ value: PRICE });

      await expect(
        contract.connect(alice).renewMembership(1n, { value: 1n })
      ).to.be.revertedWith("Pago incorrecto");
    });

    it("revierte si el token no existe", async () => {
      const { contract, alice } = await loadFixture(deployFixture);

      await expect(
        contract.connect(alice).renewMembership(999n, { value: PRICE })
      )
        .to.be.revertedWithCustomError(contract, "ERC721NonexistentToken")
        .withArgs(999n);
    });
  });

  describe("isMembershipValid / getMembership", () => {
    it("devuelve false para token inexistente", async () => {
      const { contract } = await loadFixture(deployFixture);
      expect(await contract.isMembershipValid(123n)).to.be.false;

      const info = await contract.getMembership(123n);
      expect(info.member).to.equal(ethers.ZeroAddress);
      expect(info.expiry).to.equal(0n);
      expect(info.valid).to.be.false;
    });

    it("devuelve false después de expirar", async () => {
      const { contract, alice } = await loadFixture(deployFixture);
      await contract.connect(alice).buyMembership({ value: PRICE });

      await time.increase(Number(DURATION) + 1);

      expect(await contract.isMembershipValid(1n)).to.be.false;
      const info = await contract.getMembership(1n);
      expect(info.member).to.equal(alice.address);
      expect(info.valid).to.be.false;
    });

    it("devuelve true mientras está vigente", async () => {
      const { contract, alice } = await loadFixture(deployFixture);
      await contract.connect(alice).buyMembership({ value: PRICE });

      const info = await contract.getMembership(1n);
      expect(info.member).to.equal(alice.address);
      expect(info.expiry).to.equal(await contract.expiresAt(1n));
      expect(info.valid).to.be.true;
    });
  });

  describe("withdrawFunds", () => {
    it("el owner retira todos los fondos", async () => {
      const { contract, owner, alice, bob } = await loadFixture(deployFixture);
      await contract.connect(alice).buyMembership({ value: PRICE });
      await contract.connect(bob).buyMembership({ value: PRICE });

      await expect(contract.withdrawFunds()).to.changeEtherBalances(
        [contract, owner],
        [-(PRICE * 2n), PRICE * 2n]
      );

      expect(await ethers.provider.getBalance(contract.target)).to.equal(0n);
    });

    it("revierte si no hay fondos", async () => {
      const { contract } = await loadFixture(deployFixture);
      await expect(contract.withdrawFunds()).to.be.revertedWith("Sin fondos");
    });

    it("solo el owner puede retirar", async () => {
      const { contract, alice } = await loadFixture(deployFixture);
      await contract.connect(alice).buyMembership({ value: PRICE });

      await expect(contract.connect(alice).withdrawFunds())
        .to.be.revertedWithCustomError(contract, "OwnableUnauthorizedAccount")
        .withArgs(alice.address);
    });
  });

  describe("Administración", () => {
    it("el owner actualiza el precio y se aplica a nuevas compras", async () => {
      const { contract, owner, alice } = await loadFixture(deployFixture);
      const newPrice = ethers.parseEther("0.02");

      await contract.connect(owner).setMembershipPrice(newPrice);
      expect(await contract.membershipPrice()).to.equal(newPrice);

      await expect(
        contract.connect(alice).buyMembership({ value: PRICE })
      ).to.be.revertedWith("Pago incorrecto");

      await contract.connect(alice).buyMembership({ value: newPrice });
      expect(await contract.ownerOf(1n)).to.equal(alice.address);
    });

    it("un no-owner no puede cambiar el precio", async () => {
      const { contract, alice } = await loadFixture(deployFixture);
      await expect(contract.connect(alice).setMembershipPrice(1n))
        .to.be.revertedWithCustomError(contract, "OwnableUnauthorizedAccount")
        .withArgs(alice.address);
    });

    it("rechaza un precio 0", async () => {
      const { contract } = await loadFixture(deployFixture);
      await expect(
        contract.setMembershipPrice(0n)
      ).to.be.revertedWith("Precio invalido");
    });
  });
});
