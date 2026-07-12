package com.avi.idempotency.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link FingerprintGenerator}.
 */
@DisplayName("FingerprintGenerator")
class FingerprintGeneratorTest {

    @Test
    @DisplayName("should produce a 64-character hex string")
    void shouldProduce64CharHex() {
        String fp = FingerprintGenerator.generate("key1", "POST", "/pay", null);
        assertThat(fp).hasSize(64).matches("[0-9a-f]+");
    }

    @Test
    @DisplayName("should produce the same fingerprint for identical inputs (deterministic)")
    void shouldBeDeterministic() {
        String fp1 = FingerprintGenerator.generate("key1", "POST", "/pay", "alice");
        String fp2 = FingerprintGenerator.generate("key1", "POST", "/pay", "alice");
        assertThat(fp1).isEqualTo(fp2);
    }

    @Test
    @DisplayName("should differ for different idempotency keys")
    void shouldDifferForDifferentKeys() {
        String fp1 = FingerprintGenerator.generate("key-A", "POST", "/pay", null);
        String fp2 = FingerprintGenerator.generate("key-B", "POST", "/pay", null);
        assertThat(fp1).isNotEqualTo(fp2);
    }

    @Test
    @DisplayName("should differ for different HTTP methods")
    void shouldDifferForDifferentMethods() {
        String fp1 = FingerprintGenerator.generate("key1", "POST", "/pay", null);
        String fp2 = FingerprintGenerator.generate("key1", "GET",  "/pay", null);
        assertThat(fp1).isNotEqualTo(fp2);
    }

    @Test
    @DisplayName("should differ for different URIs")
    void shouldDifferForDifferentUris() {
        String fp1 = FingerprintGenerator.generate("key1", "POST", "/payments", null);
        String fp2 = FingerprintGenerator.generate("key1", "POST", "/orders",   null);
        assertThat(fp1).isNotEqualTo(fp2);
    }

    @Test
    @DisplayName("should differ for different principals (user scoping)")
    void shouldDifferForDifferentPrincipals() {
        String fp1 = FingerprintGenerator.generate("key1", "POST", "/pay", "alice");
        String fp2 = FingerprintGenerator.generate("key1", "POST", "/pay", "bob");
        assertThat(fp1).isNotEqualTo(fp2);
    }

    @Test
    @DisplayName("null principal and 'anonymous' should produce same fingerprint")
    void nullPrincipalEqualsAnonymous() {
        String fp1 = FingerprintGenerator.generate("key1", "POST", "/pay", null);
        String fp2 = FingerprintGenerator.generate("key1", "POST", "/pay", "anonymous");
        // Both are treated as anonymous - implementation uses "anonymous" for null
        assertThat(fp1).isEqualTo(fp2);
    }

    @Test
    @DisplayName("HTTP method should be case-insensitive in fingerprint")
    void httpMethodShouldBeCaseInsensitive() {
        String fp1 = FingerprintGenerator.generate("key1", "post", "/pay", null);
        String fp2 = FingerprintGenerator.generate("key1", "POST", "/pay", null);
        assertThat(fp1).isEqualTo(fp2);
    }
}
