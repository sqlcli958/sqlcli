package com.sqlcli.cli;

import com.sqlcli.session.SessionContext;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 会话身份：让「这串改动出自同一次会话」这件事可查。
 *
 * <pre>
 * sql-cli session begin [--agent 名字]   # 生成并落盘会话 id
 * sql-cli session status                # 当前生效的是哪个，从哪来的
 * sql-cli session end
 * </pre>
 *
 * <p>状态文件按**工作目录**隔离。环境变量当主渠道是错的：agent 每个 Bash 调用都是新
 * shell，{@code export} 不跨调用；写进 profile 则永远不变，等于没有会话。
 */
@Command(name = "session",
         description = "会话身份（begin / status / end）",
         mixinStandardHelpOptions = true)
public class SessionCommand implements java.util.concurrent.Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", description = "begin | status | end")
    private String action;

    @Option(names = "--agent", description = "这次会话是哪个 agent 在跑，写进每条记录")
    private String agent;

    @Option(names = {"-j", "--json"}, description = "JSON 格式输出")
    private boolean jsonOutput;

    @Override
    public Integer call() {
        String verb = action == null ? "status" : action.toLowerCase(java.util.Locale.ROOT);
        try {
            switch (verb) {
                case "begin" -> begin();
                case "end" -> end();
                case "status" -> status();
                default -> {
                    fail("未知动作：" + action + "（只有 begin / status / end）");
                    return 2;
                }
            }
            return 0;
        } catch (java.io.IOException e) {
            fail("会话状态文件读写失败：" + e.getMessage());
            return 1;
        }
    }

    private void begin() throws java.io.IOException {
        String id = SessionContext.begin(agent);
        if (jsonOutput) {
            CliJson.printSuccess(SessionContext.describe());
        } else {
            System.out.println("会话已开始：" + id);
            System.out.println("此后这个目录下的每条执行与审批都会带上它。结束用 sql-cli session end。");
        }
    }

    private void end() throws java.io.IOException {
        boolean removed = SessionContext.end();
        if (jsonOutput) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("ended", removed);
            CliJson.printSuccess(item);
        } else {
            System.out.println(removed ? "会话已结束。" : "这个目录下没有正在进行的会话。");
        }
    }

    private void status() {
        Map<String, Object> item = SessionContext.describe();
        if (jsonOutput) {
            CliJson.printSuccess(item);
            return;
        }
        if (item.get("sessionId") == null) {
            System.out.println("没有会话（记录里的 session 会是空的）。开一个：sql-cli session begin");
            return;
        }
        item.forEach((key, value) -> System.out.println(key + ": " + value));
    }

    private void fail(String message) {
        if (jsonOutput) {
            CliJson.printFailure("bad_request", message);
        } else {
            System.err.println("Error: " + message);
        }
    }
}
