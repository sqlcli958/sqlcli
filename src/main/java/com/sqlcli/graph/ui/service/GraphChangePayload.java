package com.sqlcli.graph.ui.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * 一次待审批的图谱变更：改的是哪个对象、从什么样变成什么样。
 *
 * <p>存在 {@code approval_request.payload} 里。批准时按 {@link #after} 写回当时最新的图谱，
 * 拒绝时什么都不做——图谱从头到尾没被碰过，这正是「先落 db、后写图谱」相对
 * 「先写图谱、拒绝时再改回去」的全部价值。
 *
 * <p>{@link #before} 除了给人看 diff，还是冲突检测的依据：批准时如果对象的当前值
 * 已经不等于 before，说明提交之后有人动过它，这次重放会盖掉那次改动，所以要拦下来。
 *
 * @param targetId     变更对象 id，决定用哪个集合去定位（见 {@code GraphObjectPatch}）
 * @param operation    {@code ChangeOperation} 名，只用于展示和写变更流水
 * @param actor        提交者
 * @param baseRevision 提交时图谱的 revision，用来说明「这条变更是基于哪一版做的」
 * @param before       提交前该对象的样子；null = 当时不存在（新增）
 * @param after        期望变成的样子；null = 删除
 * @param action       见 {@link #ACTION_APPLY} / {@link #ACTION_PUBLISH}；null 当 apply
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GraphChangePayload(
        String targetId,
        String operation,
        String actor,
        long baseRevision,
        JsonNode before,
        JsonNode after,
        String action) {

    /**
     * 变更还没进图谱，批准时按 {@link #after} 重放进去，拒绝等于没发生过。
     * 别名开了 {@code approveGraph} 时走这条。
     */
    public static final String ACTION_APPLY = "apply";

    /**
     * 对象已经在图谱里、但标着候选，等人决定算不算数：批准 = 发布，拒绝 = 转 ignored。
     *
     * <p>没开 {@code approveGraph} 的别名上，agent 写入的关系直接落图谱、状态是 candidate，
     * 它同样是一件「等着我决定的事」，所以也要有一条审批排进待审批——
     * 不然它就只能在图谱那边单开一个队列，一件事两个入口。
     */
    public static final String ACTION_PUBLISH = "publish";

    /**
     * 规则提议：after 是一条 {@code PolicyRule}，批准时写进工作区的规则文件并绑定，
     * 拒绝等于没发生过。它不在图谱里，所以不走 {@code GraphObjectPatch}，
     * 见 {@code PolicyRuleProposals}。
     */
    public static final String ACTION_POLICY = "policy";

    /** 老 payload 没有这个字段，按 apply 读——那时候只有这一种。 */
    public String effectiveAction() {
        return action == null || action.isBlank() ? ACTION_APPLY : action;
    }

    public boolean isPublish() {
        return ACTION_PUBLISH.equals(effectiveAction());
    }

    public boolean isPolicy() {
        return ACTION_POLICY.equals(effectiveAction());
    }

    /** 候选关系的发布审批：对象已在图谱里，不需要 before/after。 */
    public static GraphChangePayload publish(String targetId, String actor, long revision) {
        return new GraphChangePayload(targetId, "update", actor, revision, null, null, ACTION_PUBLISH);
    }

    /**
     * 和 {@code GraphWorkspaceStore} 的 mapper 保持同一套配置：图谱节点里有
     * {@code LocalDateTime}，没有 JavaTimeModule 会序列化成一坨对象再也读不回来；
     * 忽略未知字段是为了老 payload 在模型加字段之后仍然可读。
     */
    public static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (Exception e) {
            throw new IllegalStateException("图谱变更无法序列化: " + targetId, e);
        }
    }

    /** 解不出来返回 null——老审批记录没有 payload，调用方按「不可重放」处理。 */
    public static GraphChangePayload fromJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readValue(json, GraphChangePayload.class);
        } catch (Exception e) {
            return null;
        }
    }
}
