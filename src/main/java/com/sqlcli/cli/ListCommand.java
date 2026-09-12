package com.sqlcli.cli;

import com.sqlcli.approval.ApprovalGate;
import com.sqlcli.config.AliasResolver;
import com.sqlcli.config.DatabaseConfig;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 顶层 list 命令
 * 列出数据库别名
 */
@Command(name = "list",
         description = "列出所有数据库别名",
         mixinStandardHelpOptions = true)
public class ListCommand implements Runnable {
    @Option(names = {"-j", "--json"},
            description = "JSON格式输出")
    private boolean jsonOutput;

    private final AliasResolver aliasResolver = new AliasResolver();

    @Override
    public void run() {
        var aliases = aliasResolver.listAll();

        if (aliases.isEmpty()) {
            if (jsonOutput) {
                printJsonEnvelope(java.util.List.of());
            } else {
                System.out.println("No database aliases configured.");
            }
            return;
        }

        if (jsonOutput) {
            var data = aliases.entrySet().stream().map(entry -> {
                Map<String, Object> alias = new LinkedHashMap<>();
                alias.put("name", entry.getKey());
                DatabaseConfig config = entry.getValue();
                alias.put("readonly", Boolean.TRUE.equals(config.getReadonly()));
                alias.put("graphApproval", ApprovalGate.graphMode(config).name());
                if (config.getDescription() != null && !config.getDescription().isBlank()) {
                    alias.put("description", config.getDescription());
                }
                return alias;
            }).toList();
            printJsonEnvelope(data);
        } else {
            aliases.forEach((name, config) -> {
                String desc = config.getDescription();
                String readonlyIcon = config.getReadonly() != null && config.getReadonly() ? " [RO]" : "";
                // 别名没配 graphApproval 时默认 auto（无审核）——这个默认值本身不改，
                // 只是让它在 list 里可见，人和 agent 才知道自己踩在无审核状态上。
                String graphIcon = ApprovalGate.graphMode(config) == ApprovalGate.GraphMode.manual
                        ? " [审核]" : " [自动]";
                System.out.println(name + readonlyIcon + graphIcon
                    + (desc != null && !desc.isBlank() ? " - " + desc : ""));
            });
        }
    }

    private void printJsonEnvelope(Object data) {
        CliJson.printSuccess(data);
    }
}
