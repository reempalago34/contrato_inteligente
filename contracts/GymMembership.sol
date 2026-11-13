// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

import {ERC721} from "@openzeppelin/contracts/token/ERC721/ERC721.sol";
import {Ownable} from "@openzeppelin/contracts/access/Ownable.sol";
import {ReentrancyGuard} from "@openzeppelin/contracts/utils/ReentrancyGuard.sol";
import {Strings} from "@openzeppelin/contracts/utils/Strings.sol";

/**
 * @title  GymMembership
 * @author Equipo Gimnasio
 * @notice NFT de utilidad (ERC-721) que representa una membresía de 30 días.
 *
 * ## Modelo de negocio
 * - Cada NFT es una membresía independiente: el `tokenId` identifica al socio.
 * - La fecha de expiración vive en el contrato (`expiresAt`), no en la base de datos.
 *   El backend puede consultar `isMembershipValid()` en cada reserva de clase.
 * - Renovar un NFT extiende su expiración 30 días más. Si ya expiró, se cuenta
 *   desde `block.timestamp`; si sigue vigente, se suma al tiempo restante.
 *
 * ## Decisiones de gas
 * - No se hereda de `ERC721Enumerable` (carísimo en mint/transfer): el backend
 *   indexa los eventos en su propia base de datos.
 * - Se usan custom errors en lugar de strings de `require`.
 * - Se guarda solo un `uint256` por token (`expiresAt`) en lugar de un struct.
 * - Se cachean variables de storage en memoria dentro de cada función.
 * - `_safeMint` protege a wallets contrato; el estado se escribe ANTES del mint
 *   y las funciones pagables son `nonReentrant`.
 *
 * ## Seguridad
 * - `withdrawFunds` usa checks-effects-interactions + `nonReentrant`.
 * - Las membresías son transferibles (ERC-721 estándar). Si el gimnasio quiere
 *   membresías "soulbound", se puede sobrescribir `_update` y revertir transfers.
 * - El precio se paga en la moneda nativa de la red (ETH/MATIC/etc.).
 */
contract GymMembership is ERC721, Ownable, ReentrancyGuard {
    using Strings for uint256;

    /// @notice Duración de cada periodo de membresía.
    uint256 public constant MEMBERSHIP_DURATION = 30 days;

    /// @notice Precio actual de una membresía (o renovación) en wei.
    uint256 public membershipPrice;

    /// @notice Cantidad total de NFTs emitidos. También es el último tokenId.
    uint256 public totalMinted;

    /// @notice Base para construir el tokenURI (ej. "ipfs://CID/").
    string public baseURI;

    /// @notice tokenId => timestamp de expiración (UTC, segundos).
    mapping(uint256 tokenId => uint256 expiresAt) public expiresAt;

    // ---------------------------------------------------------------------
    //                                  Errores
    // ---------------------------------------------------------------------
    error IncorrectPayment(uint256 expected, uint256 received);
    error PriceMustBePositive();
    error NotAuthorizedToRenew(uint256 tokenId, address caller);
    error NoFundsToWithdraw();
    error WithdrawFailed();

    // ---------------------------------------------------------------------
    //                                  Eventos
    // ---------------------------------------------------------------------
    /// @param member      Dirección que recibe la membresía.
    /// @param tokenId     NFT emitido.
    /// @param expiresAt   Timestamp de expiración.
    /// @param amountPaid  Wei pagados.
    event MembershipMinted(
        address indexed member,
        uint256 indexed tokenId,
        uint256 expiresAt,
        uint256 amountPaid
    );

    /// @param member         Dueño del NFT.
    /// @param tokenId        NFT renovado.
    /// @param previousExpiry Expiración anterior (puede estar en el pasado).
    /// @param newExpiry      Nueva expiración.
    /// @param amountPaid     Wei pagados.
    event MembershipRenewed(
        address indexed member,
        uint256 indexed tokenId,
        uint256 previousExpiry,
        uint256 newExpiry,
        uint256 amountPaid
    );

    event FundsWithdrawn(address indexed to, uint256 amount);
    event MembershipPriceUpdated(uint256 previousPrice, uint256 newPrice);
    event BaseURIUpdated(string newBaseURI);

    /**
     * @param initialPrice    Precio inicial en wei (debe ser > 0).
     * @param initialBaseURI  URI base de los metadatos (ej. "ipfs://CID/").
     */
    constructor(
        uint256 initialPrice,
        string memory initialBaseURI
    ) ERC721("Gym Membership", "GYMM") Ownable(msg.sender) {
        if (initialPrice == 0) revert PriceMustBePositive();
        membershipPrice = initialPrice;
        baseURI = initialBaseURI;
    }

    // ---------------------------------------------------------------------
    //                            Funciones públicas
    // ---------------------------------------------------------------------

    /**
     * @notice Compra una membresía de 30 días y recibe un NFT.
     * @dev    El precio debe coincidir exactamente con `membershipPrice`.
     * @return tokenId Identificador del NFT recién emitido.
     */
    function buyMembership()
        external
        payable
        nonReentrant
        returns (uint256 tokenId)
    {
        uint256 price = membershipPrice; // SLOAD -> memoria
        if (msg.value != price) revert IncorrectPayment(price, msg.value);

        unchecked {
            // Imposible de desbordar en la práctica (2^256 - 1).
            tokenId = ++totalMinted;
        }

        uint256 expiry = block.timestamp + MEMBERSHIP_DURATION;

        // Checks-effects-interactions: el estado se actualiza antes del mint.
        expiresAt[tokenId] = expiry;
        _safeMint(msg.sender, tokenId);

        emit MembershipMinted(msg.sender, tokenId, expiry, msg.value);
    }

    /**
     * @notice Renueva una membresía por 30 días adicionales.
     * @dev    Solo el dueño del NFT o una dirección aprobada puede renovar.
     *         Si la membresía expiró, el nuevo periodo empieza en `block.timestamp`.
     * @param  tokenId NFT a renovar.
     */
    function renewMembership(uint256 tokenId) external payable nonReentrant {
        uint256 price = membershipPrice;
        if (msg.value != price) revert IncorrectPayment(price, msg.value);

        address tokenOwner = _requireOwned(tokenId); // revierte si no existe
        if (!_isAuthorized(tokenOwner, msg.sender, tokenId)) {
            revert NotAuthorizedToRenew(tokenId, msg.sender);
        }

        uint256 previousExpiry = expiresAt[tokenId];
        // Si aún está vigente, extendemos desde su expiración; si no, desde ahora.
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

    /**
     * @notice Indica si un NFT existe y su membresía no ha expirado.
     * @dev    Función de solo lectura pensada para el backend (Python/Java).
     * @param  tokenId NFT a consultar.
     * @return true si está vigente.
     */
    function isMembershipValid(uint256 tokenId) public view returns (bool) {
        // `_ownerOf` devuelve address(0) si el token no existe; no revierte.
        if (_ownerOf(tokenId) == address(0)) return false;
        return expiresAt[tokenId] > block.timestamp;
    }

    /**
     * @notice Devuelve toda la información de una membresía en una sola llamada.
     * @dev    Evita 3 round-trips RPC desde el backend.
     * @return member  Dueño actual del NFT (address(0) si no existe).
     * @return expiry  Timestamp de expiración (0 si no existe).
     * @return valid   Si la membresía está vigente.
     */
    function getMembership(
        uint256 tokenId
    ) external view returns (address member, uint256 expiry, bool valid) {
        member = _ownerOf(tokenId);
        if (member == address(0)) return (address(0), 0, false);

        expiry = expiresAt[tokenId];
        valid = expiry > block.timestamp;
    }

    // ---------------------------------------------------------------------
    //                          Administración (onlyOwner)
    // ---------------------------------------------------------------------

    /// @notice Retira todos los fondos acumulados al owner del contrato.
    function withdrawFunds() external onlyOwner nonReentrant {
        uint256 balance = address(this).balance;
        if (balance == 0) revert NoFundsToWithdraw();

        address to = owner();
        (bool success, ) = payable(to).call{value: balance}("");
        if (!success) revert WithdrawFailed();

        emit FundsWithdrawn(to, balance);
    }

    /// @notice Actualiza el precio de la membresía.
    function setMembershipPrice(uint256 newPrice) external onlyOwner {
        if (newPrice == 0) revert PriceMustBePositive();
        uint256 previousPrice = membershipPrice;
        membershipPrice = newPrice;
        emit MembershipPriceUpdated(previousPrice, newPrice);
    }

    /// @notice Actualiza la URI base de los metadatos.
    function setBaseURI(string calldata newBaseURI) external onlyOwner {
        baseURI = newBaseURI;
        emit BaseURIUpdated(newBaseURI);
    }

    // ---------------------------------------------------------------------
    //                                  Metadata
    // ---------------------------------------------------------------------

    /**
     * @notice URI de metadatos del NFT: `{baseURI}{tokenId}.json`.
     * @dev    No usamos ERC721URIStorage para ahorrar gas en cada mint.
     */
    function tokenURI(
        uint256 tokenId
    ) public view override returns (string memory) {
        _requireOwned(tokenId); // revierte si el token no existe
        return string.concat(baseURI, tokenId.toString(), ".json");
    }
}
