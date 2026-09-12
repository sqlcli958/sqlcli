package com.sqlcli.cli;

import com.sqlcli.config.AliasResolver;
import com.sqlcli.config.DatabaseConfig;
import com.sqlcli.crypto.Sm4Config;
import com.sqlcli.util.SM4Utils;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

@Command(name = "crypto",
         description = "Crypto utilities",
         mixinStandardHelpOptions = true,
         subcommands = {CryptoCommand.Sm4Command.class})
public class CryptoCommand implements Runnable {
    @Override
    public void run() {
    }

    @Command(name = "sm4",
             description = "SM4 encrypt/decrypt",
             mixinStandardHelpOptions = true)
    public static class Sm4Command implements Callable<Integer> {
        @Option(names = "--encrypt", description = "加密输入文本")
        private boolean encrypt;

        @Option(names = "--decrypt", description = "解密输入文本")
        private boolean decrypt;

        @Option(names = "--text", required = true, description = "输入文本")
        private String text;

        @Option(names = "--alias", description = "从 aliases.yaml 读取 SM4 配置的别名")
        private String alias;

        @Option(names = "--key", description = "SM4 key")
        private String key;

        @Option(names = "--private-tag", description = "加密前缀 tag")
        private String privateTag = "ENC";

        @Option(names = "--version", description = "加密版本")
        private String version = "240606";

        private final AliasResolver aliasResolver = new AliasResolver();

        @Override
        public Integer call() {
            if (encrypt == decrypt) {
                System.err.println("Error: Specify exactly one of --encrypt or --decrypt");
                return 2;
            }
            if ((alias == null || alias.isBlank()) && (key == null || key.isBlank())) {
                System.err.println("Error: Either --alias or --key is required");
                return 2;
            }
            try {
                Sm4Config sm4Config = resolveConfig();
                String output = encrypt
                        ? SM4Utils.encrypt(sm4Config.getKey(), sm4Config.getPrivateTag(), sm4Config.getVersion(), text)
                        : SM4Utils.decrypt(sm4Config.getKey(), sm4Config.getPrivateTag(), sm4Config.getVersion(), text);
                System.out.println(output);
                return 0;
            } catch (RuntimeException e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }

        private Sm4Config resolveConfig() {
            if (alias != null && !alias.isBlank()) {
                DatabaseConfig config = aliasResolver.resolve(alias);
                if (config.getSm4Key() == null || config.getSm4Key().isBlank()) {
                    throw new IllegalArgumentException("Alias '" + alias + "' is missing sm4Key");
                }
                String resolvedPrivateTag = (config.getSm4PrivateTag() == null || config.getSm4PrivateTag().isBlank())
                        ? privateTag : config.getSm4PrivateTag();
                String resolvedVersion = (config.getSm4Version() == null || config.getSm4Version().isBlank())
                        ? version : config.getSm4Version();
                return new Sm4Config(config.getSm4Key(), resolvedPrivateTag, resolvedVersion);
            }
            String resolvedKey = key == null ? "" : key;
            if (resolvedKey.isBlank()) {
                throw new IllegalArgumentException("Either --alias or --key is required");
            }
            return new Sm4Config(resolvedKey, privateTag, version);
        }
    }
}
