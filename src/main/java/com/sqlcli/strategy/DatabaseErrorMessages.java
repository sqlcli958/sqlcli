package com.sqlcli.strategy;

/**
 * 数据库错误提示规范
 * <p>
 * 为常见数据库错误提供标准化提示信息，避免 JDBC 异常直接泄漏给用户。
 * 所有涉及数据库类型的错误提示应通过本类方法生成，保持语气和格式一致。
 */
public class DatabaseErrorMessages {

    private DatabaseErrorMessages() {
        // 工具类不允许实例化
    }

    /**
     * 驱动缺失提示
     *
     * @param dbType 数据库类型，如 mysql、oracle、clickhouse
     * @return 标准化错误提示
     */
    public static String driverMissing(String dbType) {
        return String.format(
                "JDBC driver not found for %s. Please add the driver JAR to the drivers/ directory and configure it in settings.yaml.",
                dbType);
    }

    /**
     * 认证失败提示
     *
     * @param dbType 数据库类型
     * @return 标准化错误提示
     */
    public static String authFailed(String dbType) {
        return String.format(
                "Authentication failed for %s. Please check your username and password.",
                dbType);
    }

    /**
     * 网络不可达提示
     *
     * @param host 目标主机
     * @param port 目标端口
     * @return 标准化错误提示
     */
    public static String networkUnreachable(String host, int port) {
        return String.format(
                "Cannot connect to %s:%d. Please check the host, port, and network connectivity.",
                host, port);
    }

}
