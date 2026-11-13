package com.gimnasio.web3.domain;

/** Estado local del socio, sincronizado desde la blockchain. */
public enum MemberStatus {
    PENDING,
    ACTIVE,
    EXPIRED,
    SUSPENDED
}
