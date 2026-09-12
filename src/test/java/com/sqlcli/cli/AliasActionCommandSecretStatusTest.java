package com.sqlcli.cli;

import com.sqlcli.cli.AliasActionCommand.SecretStatus;
import com.sqlcli.secret.SecretResolver;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code secret status} 的判定逻辑。
 *
 * <p>重点在区分"条目存在"与"内容可用"——空值或损坏的密码此前会被报成 available，
 * 使用者要等到真正连接时才发现密码不可用。
 */
class AliasActionCommandSecretStatusTest {

    private final SecretResolver resolver = mock(SecretResolver.class);

    private static UnaryOperator<String> env(Map<String, String> values) {
        return values::get;
    }

    @Test
    void envSecretWithValueIsUsable() {
        SecretStatus status = AliasActionCommand.inspectSecret(
                resolver, "env:DB_PASSWORD", env(Map.of("DB_PASSWORD", "s3cret")));

        assertTrue(status.exists());
        assertTrue(status.usable());
        assertNull(status.detail());
    }

    @Test
    void envSecretSetToBlankExistsButIsNotUsable() {
        SecretStatus status = AliasActionCommand.inspectSecret(
                resolver, "env:DB_PASSWORD", env(Map.of("DB_PASSWORD", "   ")));

        assertTrue(status.exists());
        assertFalse(status.usable());
        assertEquals("environment variable is set but empty", status.detail());
    }

    @Test
    void envSecretMissingIsNeitherPresentNorUsable() {
        SecretStatus status = AliasActionCommand.inspectSecret(
                resolver, "env:DB_PASSWORD", env(Map.of()));

        assertFalse(status.exists());
        assertFalse(status.usable());
        assertNull(status.detail());
    }

    @Test
    void encryptedSecretStatusMustNotAttemptDecryption() {
        when(resolver.encryptedSecretExists("prod")).thenReturn(true);

        SecretStatus status = AliasActionCommand.inspectSecret(
                resolver, "encrypted:prod", env(Map.of()));

        assertTrue(status.exists());
        assertTrue(status.usable());
        // 解密需要主密码；status 是只读查询，不能弹出交互式输入
        verify(resolver, never()).resolveSecret("encrypted:prod");
    }

    @Test
    void keyringSecretWithValueIsUsable() {
        when(resolver.keyringSecretExists("prod")).thenReturn(true);
        when(resolver.resolveSecret("keyring:prod")).thenReturn("s3cret");

        SecretStatus status = AliasActionCommand.inspectSecret(
                resolver, "keyring:prod", env(Map.of()));

        assertTrue(status.exists());
        assertTrue(status.usable());
        assertNull(status.detail());
    }

    @Test
    void keyringSecretStoredEmptyIsReportedUnusable() {
        when(resolver.keyringSecretExists("prod")).thenReturn(true);
        when(resolver.resolveSecret("keyring:prod")).thenReturn("");

        SecretStatus status = AliasActionCommand.inspectSecret(
                resolver, "keyring:prod", env(Map.of()));

        assertTrue(status.exists());
        assertFalse(status.usable());
        assertEquals("stored secret is empty; run 'secret set' again", status.detail());
    }

    @Test
    void keyringSecretThatCannotBeReadIsReportedUnusableWithReason() {
        when(resolver.keyringSecretExists("prod")).thenReturn(true);
        when(resolver.resolveSecret("keyring:prod"))
                .thenThrow(new RuntimeException("DPAPI decryption failed"));

        SecretStatus status = AliasActionCommand.inspectSecret(
                resolver, "keyring:prod", env(Map.of()));

        assertTrue(status.exists());
        assertFalse(status.usable());
        assertEquals("stored secret cannot be read: DPAPI decryption failed", status.detail());
    }

    @Test
    void keyringSecretMissingSkipsResolution() {
        when(resolver.keyringSecretExists("prod")).thenReturn(false);

        SecretStatus status = AliasActionCommand.inspectSecret(
                resolver, "keyring:prod", env(Map.of()));

        assertFalse(status.exists());
        assertFalse(status.usable());
        verify(resolver, never()).resolveSecret("keyring:prod");
    }

    @Test
    void unknownSecretSchemeIsReportedMissingRatherThanUsable() {
        SecretStatus status = AliasActionCommand.inspectSecret(
                resolver, "vault:prod", env(Map.of()));

        assertFalse(status.exists());
        assertFalse(status.usable());
    }
}
