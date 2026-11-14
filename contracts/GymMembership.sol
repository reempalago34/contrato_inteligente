// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

import {ERC721} from "@openzeppelin/contracts/token/ERC721/ERC721.sol";
import {Ownable} from "@openzeppelin/contracts/access/Ownable.sol";
import {ReentrancyGuard} from "@openzeppelin/contracts/utils/ReentrancyGuard.sol";

/**
 * @title GymMembership
 * @notice NFT ERC-721 que representa una membresía de 30 días.
 *
 * Lo esencial:
 * - buyMembership(): compra una membresía pagando en la moneda nativa.
 * - renewMembership(tokenId): añade 30 días.
 * - isMembershipValid(tokenId): indica si sigue activa.
 * - withdrawFunds(): el owner retira los fondos.
 *
 * La fecha de expiración vive en el contrato. El backend solo consulta.
 */
contract GymMembership is ERC721, Ownable, ReentrancyGuard {
    uint256 public constant MEMBERSHIP_DURATION = 30 days;

    uint256 public membershipPrice;
    uint256 public totalMinted;

    /// tokenId => timestamp de expiración (segundos UNIX)
    mapping(uint256 tokenId => uint256 expiresAt) public expiresAt;

    event MembershipMinted(
        address indexed member,
        uint256 indexed tokenId,
        uint256 expiresAt,
        uint256 amountPaid
    );

    event MembershipRenewed(
        address indexed member,
        uint256 indexed tokenId,
        uint256 previousExpiry,
        uint256 newExpiry,
        uint256 amountPaid
    );

    constructor(
        uint256 initialPrice
    ) ERC721("Gym Membership", "GYMM") Ownable(msg.sender) {
        require(initialPrice > 0, "Precio invalido");
        membershipPrice = initialPrice;
    }

    /// @notice Compra una membresía de 30 días y recibe un NFT.
    function buyMembership()
        external
        payable
        nonReentrant
        returns (uint256 tokenId)
    {
        require(msg.value == membershipPrice, "Pago incorrecto");

        tokenId = ++totalMinted;
        uint256 expiry = block.timestamp + MEMBERSHIP_DURATION;
        expiresAt[tokenId] = expiry;

        _safeMint(msg.sender, tokenId);
        emit MembershipMinted(msg.sender, tokenId, expiry, msg.value);
    }

    /// @notice Renueva una membresía por 30 días más.
    function renewMembership(uint256 tokenId) external payable nonReentrant {
        require(msg.value == membershipPrice, "Pago incorrecto");

        address tokenOwner = ownerOf(tokenId);
        require(_isAuthorized(tokenOwner, msg.sender, tokenId), "No autorizado");

        uint256 previousExpiry = expiresAt[tokenId];
        uint256 start = previousExpiry > block.timestamp
            ? previousExpiry
            : block.timestamp;
        uint256 newExpiry = start + MEMBERSHIP_DURATION;

        expiresAt[tokenId] = newExpiry;
        emit MembershipRenewed(
            tokenOwner,
            tokenId,
            previousExpiry,
            newExpiry,
            msg.value
        );
    }

    /// @notice Devuelve true si el token existe y no ha expirado.
    function isMembershipValid(uint256 tokenId) public view returns (bool) {
        return
            _ownerOf(tokenId) != address(0) &&
            expiresAt[tokenId] > block.timestamp;
    }

    /// @notice Dueño, expiración y validez en una sola llamada.
    function getMembership(
        uint256 tokenId
    ) external view returns (address member, uint256 expiry, bool valid) {
        member = _ownerOf(tokenId);
        if (member == address(0)) return (address(0), 0, false);

        expiry = expiresAt[tokenId];
        valid = expiry > block.timestamp;
    }

    /// @notice El owner retira todos los fondos del contrato.
    function withdrawFunds() external onlyOwner nonReentrant {
        uint256 balance = address(this).balance;
        require(balance > 0, "Sin fondos");

        (bool success, ) = payable(owner()).call{value: balance}("");
        require(success, "Retiro fallido");
    }

    /// @notice El owner actualiza el precio de la membresía.
    function setMembershipPrice(uint256 newPrice) external onlyOwner {
        require(newPrice > 0, "Precio invalido");
        membershipPrice = newPrice;
    }
}
