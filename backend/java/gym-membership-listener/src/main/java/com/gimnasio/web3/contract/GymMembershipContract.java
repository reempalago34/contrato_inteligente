package com.gimnasio.web3.contract;

import io.reactivex.Flowable;
import org.web3j.abi.EventEncoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.Event;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.RemoteFunctionCall;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.tx.Contract;
import org.web3j.tx.Contract.EventValuesWithLog;
import org.web3j.tx.ReadonlyTransactionManager;
import org.web3j.tx.gas.StaticGasProvider;

import java.math.BigInteger;
import java.util.Arrays;

/**
 * Wrapper mínimo escrito a mano para GymMembership (equivalente al que genera
 * `web3j generate solidity`). Solo incluye lo que necesita el listener:
 *  - Eventos MembershipMinted / MembershipRenewed.
 *  - Lectura isMembershipValid (útil para endpoints de validación).
 *
 * Si prefieres el wrapper completo:
 *   web3j generate solidity \
 *     -a src/main/resources/abi/GymMembership.abi \
 *     -b src/main/resources/abi/GymMembership.bin \
 *     -o src/main/java -p com.gimnasio.web3.generated
 *
 * Importante: este wrapper es SOLO LECTURA. Para enviar transacciones
 * (comprar/renovar) usa el backend con credenciales o el frontend con wagmi/ethers.
 */
public class GymMembershipContract extends Contract {

    public static final String FUNC_IS_MEMBERSHIP_VALID = "isMembershipValid";

    /**
     * MembershipMinted(address indexed member, uint256 indexed tokenId,
     *                  uint256 expiresAt, uint256 amountPaid)
     */
    public static final Event MEMBERSHIP_MINTED_EVENT =
            new Event(
                    "MembershipMinted",
                    Arrays.<TypeReference<?>>asList(
                            new TypeReference<Address>(true) {},
                            new TypeReference<Uint256>(true) {},
                            new TypeReference<Uint256>() {},
                            new TypeReference<Uint256>() {}));

    /**
     * MembershipRenewed(address indexed member, uint256 indexed tokenId,
     *                   uint256 previousExpiry, uint256 newExpiry, uint256 amountPaid)
     */
    public static final Event MEMBERSHIP_RENEWED_EVENT =
            new Event(
                    "MembershipRenewed",
                    Arrays.<TypeReference<?>>asList(
                            new TypeReference<Address>(true) {},
                            new TypeReference<Uint256>(true) {},
                            new TypeReference<Uint256>() {},
                            new TypeReference<Uint256>() {},
                            new TypeReference<Uint256>() {}));

    /** Constructor de solo lectura (no necesita private key). */
    public GymMembershipContract(String contractAddress, Web3j web3j) {
        super(
                "",
                contractAddress,
                web3j,
                new ReadonlyTransactionManager(
                        web3j, "0x0000000000000000000000000000000000000000"),
                new StaticGasProvider(BigInteger.ZERO, BigInteger.ZERO));
    }

    // ------------------------------------------------------------------
    // Lecturas
    // ------------------------------------------------------------------
    public RemoteFunctionCall<Boolean> isMembershipValid(BigInteger tokenId) {
        final Function function =
                new Function(
                        FUNC_IS_MEMBERSHIP_VALID,
                        Arrays.<Type>asList(new Uint256(tokenId)),
                        Arrays.<TypeReference<?>>asList(new TypeReference<Bool>() {}));
        return executeRemoteCallSingleValueReturn(function, Boolean.class);
    }

    // ------------------------------------------------------------------
    // Eventos
    // ------------------------------------------------------------------
    public Flowable<MembershipMintedEventResponse> membershipMintedEventFlowable(
            DefaultBlockParameter startBlock, DefaultBlockParameter endBlock) {
        EthFilter filter = new EthFilter(startBlock, endBlock, getContractAddress());
        filter.addSingleTopic(EventEncoder.encode(MEMBERSHIP_MINTED_EVENT));
        return membershipMintedEventFlowable(filter);
    }

    public Flowable<MembershipMintedEventResponse> membershipMintedEventFlowable(
            EthFilter filter) {
        return web3j.ethLogFlowable(filter)
                .map(
                        log -> {
                            EventValuesWithLog eventValues =
                                    extractEventParametersWithLog(MEMBERSHIP_MINTED_EVENT, log);
                            MembershipMintedEventResponse response =
                                    new MembershipMintedEventResponse();
                            response.log = eventValues.getLog();
                            response.member =
                                    (String) eventValues.getIndexedValues().get(0).getValue();
                            response.tokenId =
                                    (BigInteger)
                                            eventValues.getIndexedValues().get(1).getValue();
                            response.expiresAt =
                                    (BigInteger)
                                            eventValues.getNonIndexedValues().get(0).getValue();
                            response.amountPaid =
                                    (BigInteger)
                                            eventValues.getNonIndexedValues().get(1).getValue();
                            return response;
                        });
    }

    public Flowable<MembershipRenewedEventResponse> membershipRenewedEventFlowable(
            DefaultBlockParameter startBlock, DefaultBlockParameter endBlock) {
        EthFilter filter = new EthFilter(startBlock, endBlock, getContractAddress());
        filter.addSingleTopic(EventEncoder.encode(MEMBERSHIP_RENEWED_EVENT));
        return membershipRenewedEventFlowable(filter);
    }

    public Flowable<MembershipRenewedEventResponse> membershipRenewedEventFlowable(
            EthFilter filter) {
        return web3j.ethLogFlowable(filter)
                .map(
                        log -> {
                            EventValuesWithLog eventValues =
                                    extractEventParametersWithLog(MEMBERSHIP_RENEWED_EVENT, log);
                            MembershipRenewedEventResponse response =
                                    new MembershipRenewedEventResponse();
                            response.log = eventValues.getLog();
                            response.member =
                                    (String) eventValues.getIndexedValues().get(0).getValue();
                            response.tokenId =
                                    (BigInteger)
                                            eventValues.getIndexedValues().get(1).getValue();
                            response.previousExpiry =
                                    (BigInteger)
                                            eventValues.getNonIndexedValues().get(0).getValue();
                            response.newExpiry =
                                    (BigInteger)
                                            eventValues.getNonIndexedValues().get(1).getValue();
                            response.amountPaid =
                                    (BigInteger)
                                            eventValues.getNonIndexedValues().get(2).getValue();
                            return response;
                        });
    }

    /** El log viene incluido en la respuesta para idempotencia (txHash + logIndex). */
    public static class MembershipMintedEventResponse {
        public Log log;
        public String member;
        public BigInteger tokenId;
        public BigInteger expiresAt;
        public BigInteger amountPaid;
    }

    public static class MembershipRenewedEventResponse {
        public Log log;
        public String member;
        public BigInteger tokenId;
        public BigInteger previousExpiry;
        public BigInteger newExpiry;
        public BigInteger amountPaid;
    }
}
