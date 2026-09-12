package com.sqlcli.cli;

import com.sqlcli.secret.SecretResolver;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AliasCommandTest {

    @Test
    void refusesToOverwriteExistingInteractiveSecret() {
        SecretResolver resolver = mock(SecretResolver.class);
        when(resolver.keyringSecretExists("existing-db")).thenReturn(true);

        assertThrows(IllegalArgumentException.class, () ->
                AliasCommand.requireSecretAvailable(resolver, "keyring:existing-db", "existing-db"));
    }
}
