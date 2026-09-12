package com.sqlcli.graph.workspace;

import lombok.Data;

@Data
public class WorkspaceStats {
    private int schemas;
    private int tables;
    private int columns;
    /** 含 ignored——「拒绝=删除」改成「拒绝=转状态」之后，这个总数就是存了多少条边的结构事实。 */
    private int relations;
    /** relations 里状态为 {@link GraphStatus#ignored} 的部分，单独列出来给人看，不从 relations 里减掉。 */
    private int ignoredRelations;
    private int metrics;
    private int validationIssues;
}
