package com.sqlcli.connection;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 连接测试结果
 */
@Getter
public class ConnectionTestResult {
    private final boolean success;
    private final String message;
    private final String rootCause;
    private final List<String> hints;
    private final Map<String, String> extraInfo;
    private final String serverVersion;

    private ConnectionTestResult(boolean success, String message, String rootCause, List<String> hints) {
        this(success, message, rootCause, hints, Collections.emptyMap(), null);
    }

    private ConnectionTestResult(boolean success, String message, String rootCause, List<String> hints, Map<String, String> extraInfo) {
        this(success, message, rootCause, hints, extraInfo, null);
    }

    private ConnectionTestResult(boolean success, String message, String rootCause, List<String> hints, Map<String, String> extraInfo, String serverVersion) {
        this.success = success;
        this.message = message;
        this.rootCause = rootCause;
        this.hints = hints;
        this.extraInfo = extraInfo != null ? extraInfo : Collections.emptyMap();
        this.serverVersion = serverVersion;
    }

    public static ConnectionTestResult success() {
        return new ConnectionTestResult(true, "Connection successful", "", new ArrayList<>());
    }

    /**
     * Create a new ConnectionTestResult with the same fields but with server version set.
     */
    public ConnectionTestResult withServerVersion(String serverVersion) {
        return new ConnectionTestResult(this.success, this.message, this.rootCause, this.hints, this.extraInfo, serverVersion);
    }

    public static ConnectionTestResult failure(String message, String rootCause, List<String> hints) {
        return new ConnectionTestResult(false, message, rootCause, hints);
    }
}
