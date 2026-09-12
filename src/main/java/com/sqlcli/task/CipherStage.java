package com.sqlcli.task;

import com.sqlcli.crypto.Sm4Config;
import com.sqlcli.sql.SqlCipherParser;
import com.sqlcli.util.SM4Utils;

import java.util.ArrayList;
import java.util.List;

/**
 * SM4 加密改写在执行前，结果解密在执行后——同一个关注点的两端，是唯一有 {@code after}
 * 的 Stage。之前分散在 {@code Sm4SqlCipherParser}（改写）和渲染器（解密）两处，
 * 现在对称地放在同一个类的前后两半。
 */
final class CipherStage implements SqlTaskStage {

    private final SqlCipherParser cipherParser;

    CipherStage(SqlCipherParser cipherParser) {
        this.cipherParser = cipherParser;
    }

    @Override
    public String name() {
        return "cipher";
    }

    @Override
    public void before(SqlTaskContext ctx) {
        ctx.sql = cipherParser.rewrite(ctx.sql, ctx.request.options());
    }

    @Override
    public void after(SqlTaskContext ctx) {
        Sm4Config sm4Config = ctx.request.options().getSm4Config();
        if (sm4Config == null || ctx.resultRows.isEmpty()) {
            return;
        }
        List<String> names = ctx.resultColumns.stream().map(SqlTaskResult.Column::name).toList();
        boolean[] decryptable = new boolean[names.size()];
        boolean anyDecryptable = false;
        for (int i = 0; i < names.size(); i++) {
            decryptable[i] = ctx.request.options().shouldDecrypt(names.get(i));
            anyDecryptable |= decryptable[i];
        }
        if (!anyDecryptable) {
            return;
        }
        List<List<Object>> decrypted = new ArrayList<>(ctx.resultRows.size());
        for (List<Object> row : ctx.resultRows) {
            List<Object> newRow = new ArrayList<>(row);
            for (int i = 0; i < decryptable.length; i++) {
                if (decryptable[i] && newRow.get(i) != null) {
                    newRow.set(i, SM4Utils.decrypt(sm4Config.getKey(), sm4Config.getPrivateTag(),
                            sm4Config.getVersion(), String.valueOf(newRow.get(i))));
                }
            }
            decrypted.add(newRow);
        }
        ctx.resultRows = decrypted;
    }
}
