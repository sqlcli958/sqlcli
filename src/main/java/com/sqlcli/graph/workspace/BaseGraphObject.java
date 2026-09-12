package com.sqlcli.graph.workspace;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.LocalDateTime;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public abstract class BaseGraphObject {
    private String id;
    private GraphObjectKind kind;
    private int version = 1;
    private GraphStatus status = GraphStatus.discovered;
    private Double confidence;
    private Boolean verified;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private GraphActor createdBy = GraphActor.system;
    private GraphActor updatedBy = GraphActor.system;
    private Map<String, Object> attributes = new LinkedHashMap<>();

    protected void init(GraphObjectKind objectKind, String objectId, GraphActor actor) {
        LocalDateTime now = LocalDateTime.now();
        this.kind = objectKind;
        this.id = objectId;
        this.createdAt = now;
        this.updatedAt = now;
        this.createdBy = actor;
        this.updatedBy = actor;
    }

    /**
     * 记下「谁最后动了这个对象的内容、什么时候动的」。
     *
     * <p><b>{@link GraphActor#system} 的记账动作不算数，直接返回。</b>回写行数、建索引、
     * 跑校验是机器的账，不是有人主张的内容变更——判据跟
     * {@code WorkspaceMutationService.requiresApproval}（system 一律不过审批）是同一条。
     * 实测 {@code erp_prop_report} 被 agent 写满了描述、值域和 grain，{@code updatedBy}
     * 却是 {@code system}：一次 {@code describe --refresh-row-count} 就把 actor 盖掉了。
     *
     * <p>{@code updatedAt} 跟着一起冻结，不是漏了：{@code describe} 的完整度那一行
     * 把它当「最近整理时间」打出来（「有人整理过（最近变更 X）」）。只冻 {@code updatedBy}
     * 等于换一个字段继续撒谎——一个说 agent 补的，一个说今天补的，而今天发生的只有一次
     * COUNT(*)。行数本身照写照存，「什么时候统计的」由变更流水回答。
     */
    public void touch(GraphActor actor) {
        if (actor == GraphActor.system) {
            return;
        }
        this.updatedAt = LocalDateTime.now();
        this.updatedBy = actor;
    }
}
