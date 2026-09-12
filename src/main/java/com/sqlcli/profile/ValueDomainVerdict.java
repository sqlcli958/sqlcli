package com.sqlcli.profile;

import java.util.List;

/**
 * 一列的值域交叉判定结果。
 *
 * @param proposedEnumValues 建议写进 {@code valueHints.enumValues} 的值域，
 *                           以库里真实出现的值为准，能配上语义的带语义
 * @param onlyInDatabase     库里有、注释和代码都没写的值——**注释过期，或者有脏数据**
 * @param onlyInDeclared     注释/代码里有、库里从没出现过的值——可能是废弃分支，
 *                           也可能只是测试库数据不全
 * @param dead               这个字段实际没在用（全 NULL，或者非空行全是同一个值）
 * @param unchanged          建议值域与图谱里已有的取值集合相同，没有变化
 */
public record ValueDomainVerdict(
        String columnName,
        boolean skipped,
        String skipReason,
        List<String> proposedEnumValues,
        List<String> onlyInDatabase,
        List<String> onlyInDeclared,
        /** 注释和代码里**一个取值都没声明过**——这时 onlyInDatabase 不是异常，只是缺值域 */
        boolean nothingDeclared,
        boolean dead,
        double nullRatio,
        long distinctCount,
        boolean unchanged) {

    public static ValueDomainVerdict skipped(String columnName, String reason) {
        return new ValueDomainVerdict(columnName, true, reason,
                List.of(), List.of(), List.of(), true, false, 0, 0, true);
    }

    /**
     * 这一列值不值得摆到人面前。
     *
     * <p>**只为有发现的列建审批**——三源一致、且图谱里已经写着同样的值域，
     * 那就没有任何需要人决定的东西，建一条审批只是往队列里塞噪音。
     */
    public boolean hasFinding() {
        if (skipped) return false;
        return !onlyInDatabase.isEmpty() || !onlyInDeclared.isEmpty() || dead || !unchanged;
    }

    /**
     * 有发现、但没有值域可写（比如整列全 NULL）——提不出审批，只能报告。
     *
     * <p>这类发现的正式归宿是 `schema eval` 的 finding 表（清单 E2/E3）；
     * 在那之前它只出现在命令输出里，所以命令必须明说有几条只报告不提交，
     * 否则就是「发现了但没人接手」——这个模式在本仓库已经出现过三次。
     */
    public boolean reportOnly() {
        return hasFinding() && proposedEnumValues.isEmpty();
    }

    /**
     * 一句话说清这一列发现了什么，直接进审批摘要。
     *
     * <p>按严重程度排序：脏数据/注释过期最要紧（它会让 Agent 按错的值域写 WHERE），
     * 「字段没在用」次之（它会让 Agent 拿一个死字段做判断），最后才是「补一份值域」。
     */
    public String summary() {
        if (skipped) return columnName + "：跳过（" + skipReason + "）";
        StringBuilder sb = new StringBuilder(columnName).append("：");
        List<String> parts = new java.util.ArrayList<>();
        if (!onlyInDatabase.isEmpty()) {
            // 这两种情况严重性差很远，说法必须分开：
            // 「注释声明了 0/1、库里冒出个 2」是注释过期或脏数据；
            // 「注释压根没声明过」只是缺一份值域，不是异常。
            parts.add(nothingDeclared
                    ? "注释未声明值域，库里实际有 " + onlyInDatabase
                    : "库里存在未声明的值 " + onlyInDatabase);
        }
        if (dead) {
            parts.add(nullRatio >= 1.0 ? "全为 NULL，实际未启用"
                    : "非空行只有一个取值，实际未启用");
        }
        if (!onlyInDeclared.isEmpty()) {
            parts.add("声明了但库里从未出现 " + onlyInDeclared);
        }
        if (parts.isEmpty() && !unchanged) {
            parts.add("补一份值域（" + proposedEnumValues.size() + " 个取值）");
        }
        return sb.append(String.join("；", parts)).toString();
    }
}
