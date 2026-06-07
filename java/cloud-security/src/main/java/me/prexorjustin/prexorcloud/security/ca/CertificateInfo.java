package me.prexorjustin.prexorcloud.security.ca;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;

public record CertificateInfo(
        String subject,
        String issuer,
        Instant validFrom,
        Instant validTo,
        BigInteger serialNumber,
        List<String> subjectAlternativeNames) {}
