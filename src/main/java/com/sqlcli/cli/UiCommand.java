package com.sqlcli.cli;

import com.sqlcli.graph.ui.GraphUiOptions;
import com.sqlcli.graph.ui.GraphUiServer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.util.concurrent.Callable;

/** Starts the local schema graph web UI. */
@Command(name = "ui", description = "启动图谱 Web UI", mixinStandardHelpOptions = true)
public class UiCommand implements Callable<Integer> {

    @Option(names = "--alias", description = "直接打开指定别名的图谱")
    private String alias;

    @Option(names = "--port", defaultValue = "9999", description = "监听端口（默认：9999；0 表示随机端口）")
    private int port;

    @Option(names = "--no-open", description = "启动后不自动打开浏览器")
    private boolean noOpen;

    @Override
    public Integer call() {
        GraphUiOptions options = new GraphUiOptions();
        options.setAlias(alias);
        options.setPort(port);
        options.setNoOpen(noOpen);
        try {
            GraphUiServer server = GraphUiServer.createAndStart(options);
            System.out.println("  Stop:  press Ctrl+C");
            server.awaitTermination();
            return 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 130;
        } catch (IOException e) {
            System.err.println("Graph UI failed: " + e.getMessage());
            return 1;
        }
    }
}
