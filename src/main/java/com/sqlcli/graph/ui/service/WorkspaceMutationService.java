package com.sqlcli.graph.ui.service;

import com.sqlcli.approval.ApprovalGate;
import com.sqlcli.graph.workspace.*;
import com.sqlcli.runstate.ApprovalBatchRow;
import com.sqlcli.runstate.ApprovalRow;
import com.sqlcli.runstate.RunStateStore;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * 统一所有写操作，UI API 和 CLI 共用相同校验及写入路径。
 */
public class WorkspaceMutationService {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(WorkspaceMutationService.class);

    private final GraphWorkspaceStore store;
    private final WorkspaceValidator validator;
    private final WorkspaceLockManager lockManager;
    private final RelationValidator relationValidator;
    /**
     * 查同对象待审批时翻多少条。图谱审批不阻塞、裁决前一直挂着，正常量级是个位数；
     * 真到几百条待审批时，重复提交已经不是最要紧的问题了。
     */
    private static final int PENDING_LOOKUP_LIMIT = 200;

    private final ObjectMapper hashMapper;
    private final RunStateStore runState = new RunStateStore();
    private final ApprovalGate approvalGate = new ApprovalGate();

    public WorkspaceMutationService(GraphWorkspaceStore store, WorkspaceValidator validator,
                                    WorkspaceLockManager lockManager) {
        this.store = store;
        this.validator = validator;
        this.lockManager = lockManager;
        this.relationValidator = new RelationValidator();
        this.hashMapper = new ObjectMapper();
        this.hashMapper.registerModule(new JavaTimeModule());
        this.hashMapper.configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);
        this.hashMapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    public MutationResult mutate(String alias, long expectedRevision, GraphActor actor,
            String reason, WorkspaceMutation mutation) {
        return mutate(alias, expectedRevision, actor, reason, null, mutation);
    }

    /**
     * @param targetId 变更对象 id（目前只有关系操作传）；挂到审批记录上，
     *                 评审页详情端点才能把候选的 diff / 证据 / 校验拼出来。
     */
    public MutationResult mutate(String alias, long expectedRevision, GraphActor actor,
            String reason, String targetId, WorkspaceMutation mutation) {
        return mutate(alias, expectedRevision, actor, reason, targetId, ApprovalMode.AUTO, mutation);
    }

    /** 这次写入跟图谱审批的关系。两种，不能用一个 boolean 表达。 */
    public enum ApprovalMode {
        /** 看别名的 `graphApproval`（auto / manual）——除了重放批准结果，所有写入走这条 */
        AUTO,
        /** 不受管辖：批准一条待审批变更时由 {@link #applyApproved} 走这条路落盘，
         *  审批已经做过了，再挡一次就永远批不完 */
        NEVER
    }

    private MutationResult mutate(String alias, long expectedRevision, GraphActor actor,
            String reason, String targetId, ApprovalMode mode, WorkspaceMutation mutation) {
        return mutate(alias, expectedRevision, actor, reason, targetId, mode, null, mutation);
    }

    /**
     * @param approvalId 放行这次写入的审批；写进变更流水，用来回答「这条更新是谁批的」
     */
    private MutationResult mutate(String alias, long expectedRevision, GraphActor actor,
            String reason, String targetId, ApprovalMode mode, Long approvalId,
            WorkspaceMutation mutation) {
        // 归入批次的条目一律先暂存：一批的意义就是「一起裁决、一起落地」，
        // 别名是 auto 也不能让其中一条自己先落地。
        boolean batched = mode != ApprovalMode.NEVER && actor != GraphActor.system
                && com.sqlcli.session.SessionContext.batchId() != null;
        boolean gate = switch (mode) {
            case NEVER -> false;
            case AUTO -> batched || requiresApproval(alias, actor);
        };
        try (WriteLockGuard lock = acquireWriteLock(alias)) {
            GraphWorkspace workspace = store.load(alias);
            checkRevision(workspace, expectedRevision);
            String beforeHash = hashWorkspace(workspace);
            MutationOutcome outcome = mutation.apply(workspace);
            if (!gate) {
                MutationResult result = commitPrepared(alias, workspace, beforeHash, actor, reason,
                        outcome, approvalId);
                return queueCandidateReview(alias, workspace, actor, result);
            }
            return stageForApproval(alias, workspace, actor, reason, outcome);
        } catch (WorkspaceLockException e) {
            return MutationResult.failure("lock error: " + e.getMessage());
        } catch (Exception e) {
            return MutationResult.failure("save error: " + e.getMessage());
        }
    }

    /**
     * Commit a workspace already changed while the caller holds this alias's write lock.
     * Used by the import pipeline, which keeps the lock across its load/merge/commit flow.
     */
    public MutationResult commitLocked(String alias, long expectedRevision, GraphActor actor,
            String reason, GraphWorkspace changed, MutationOutcome outcome) {
        try {
            GraphWorkspace current = store.load(alias);
            checkRevision(current, expectedRevision);
            return commitPrepared(alias, changed, hashWorkspace(current), actor, reason, outcome, null);
        } catch (WorkspaceLockException e) {
            return MutationResult.failure("lock error: " + e.getMessage());
        } catch (Exception e) {
            return MutationResult.failure("save error: " + e.getMessage());
        }
    }

    /**
     * 这次写入要不要先过审批。
     *
     * <p>{@link GraphActor#system} 一律不过：建索引、跑校验、回写行数是记账动作，
     * 结果由机器决定，把它们塞进审批队列的后果是「不批准就不能跑 {@code schema validate}」——
     * 审批要管的是有人主张的内容变更。
     *
     * <p>判据只有别名的 {@code graphApproval}。命令自己不许再开小灶：一条直通、一条排队
     * 并存的结果是两条路径的基线错位（审批 #154 就是这么挂的）。
     */
    private static boolean requiresApproval(String alias, GraphActor actor) {
        return actor != GraphActor.system && ApprovalGate.isEnabled(alias, ApprovalGate.Kind.GRAPH);
    }

    /**
     * 图谱审批：变更不落盘，存进审批记录等人放行。
     *
     * <h2>先落 db、后写图谱</h2>
     * 反过来做过一版——先写图谱、再挂一条待审批。问题出在拒绝：图谱已经改了，「拒绝」
     * 除了记一笔什么也做不了，表 / 字段描述这类没有候选态的改动根本回不去。
     * 变更先存进 {@code approval_request.payload}、批准时才写图谱，拒绝就天然等于没发生过。
     *
     * <p>存的不是整份工作区快照，而是**被改的那一个对象**的 before/after
     * （见 {@link GraphObjectPatch}）：快照在批准时整个盖回去，会把提交到批准之间别人做的
     * 改动一起抹掉；单对象补丁能重放到当时最新的图谱上，还能顺带做冲突检测。
     *
     * <p>不挡 {@link #commitLocked}：那是导入管线，一次导入成千上万个对象，
     * 逐条审批没有意义——要管导入，管的是导入这个动作本身。
     */
    private MutationResult stageForApproval(String alias, GraphWorkspace changed, GraphActor actor,
            String reason, MutationOutcome outcome) throws Exception {
        String targetId = outcome.targetId();
        if (!GraphObjectPatch.supports(targetId)) {
            return MutationResult.failure("图谱审批已开启，但这次变更（" + targetId
                    + "）不是针对单个图谱对象的，无法暂存待审批。整份快照导入属于这一类，"
                    + "它跟建索引、跑校验一样不走审批。");
        }
        // 队列里不该躺着一条批准之后必然校验失败的变更，提交时就要拦下来
        if (outcome.operation() != ChangeOperation.verify) {
            List<String> errors = validateWorkspace(changed);
            if (!errors.isEmpty()) return MutationResult.failure(errors);
        }
        // ponytail: 为了拿 before 再读一次工作区。此刻还在写锁里，读的就是刚才那份文件，
        // 代价是一次目录扫描——而这条变更接下来要等人看几分钟，不值得为它加一层缓存。
        GraphWorkspace pristine = store.load(alias);
        long baseRevision = pristine.getManifest().getRevision();
        String summary = reason == null || reason.isBlank() ? "图谱变更" : reason;

        // 逐个对象取 diff。**必须逐个取**：只看主对象的话，`add-term --map` 这种
        // 「术语没变、只新增了边」的提交会被判成 no-op，审批都建不出来。
        List<GraphChangePayload> payloads = new ArrayList<>();
        for (String id : outcome.allTargets()) {
            if (!GraphObjectPatch.supports(id)) continue;
            JsonNode before = snapshot(GraphObjectPatch.read(pristine, id));
            JsonNode after = snapshot(GraphObjectPatch.read(changed, id));
            if (sameNode(before, after)) continue;
            payloads.add(new GraphChangePayload(id, outcome.operation().name(),
                    actor == null ? null : actor.name(), baseRevision, before, after,
                    GraphChangePayload.ACTION_APPLY));
        }
        if (payloads.isEmpty()) {
            // 什么都没改，跟未开审批时的 no-op 短路保持同一个返回
            return MutationResult.success(baseRevision, null, targetId);
        }
        // 队列里已经挂着的同对象提交。**必须在这里比一次**：上面的 diff 基线是图谱，
        // 而 manual 别名上前一次提交图谱一个字节没动，所以同一条命令跑第二遍时
        // before 仍然是「不存在」，会原样再生成一份 payload。不比就等于
        // **同一条命令在 auto 上幂等、在 manual 上每跑一次多一份待审批**。
        // 真实事故：审批 #398，一个 agent 68 秒内提交同一条术语三次，
        // 队列里三张标题一样的卡，批准第一批之后另外两批全被冲突检测挡死。
        List<ApprovalRow> queued = pendingGraphApprovals(alias, payloads);
        if (!queued.isEmpty() && allAlreadyQueued(payloads, queued)) {
            // 内容一字不差：这次提交什么也不该发生，返回原来那个审批号。
            // 报新号会让提交方以为又提了一条，报「没有修改」又会让它以为写丢了。
            return MutationResult.pending(queued.get(0).id(), targetId, baseRevision);
        }
        // 内容变了 = 提交方改了主意。auto 别名上第二次写就是覆盖第一次，
        // manual 上的等价物是让旧的那份作废——并排挂两条只会让人去队列里认哪张是最新的。
        supersede(alias, queued, payloads);
        if (payloads.size() == 1) {
            GraphChangePayload payload = payloads.get(0);
            long id = approvalGate.request(alias, ApprovalGate.Kind.GRAPH, summary, reason,
                    null, payload.targetId(), payload.toJson());
            return MutationResult.pending(id, targetId, baseRevision);
        }
        return stageBatch(alias, summary, reason, payloads, targetId, baseRevision);
    }

    /**
     * 落盘之后目标还是一条候选关系时，为它排一条待审批。
     *
     * <p>候选边是 agent 写进图谱、但标着「还不算数」的东西——它跟一次待批准的变更是同一类
     * 事情（等着人决定），所以要进同一个队列。原来它只在图谱那边单开一个列表，
     * 结果是一件等着我决定的事有两个入口，两边都得看一遍。
     *
     * <p>只在**没开图谱审批**的别名上会发生：开了的话写入本身就排了队，
     * 批准时 {@code publishIfCandidate} 直接定级，压根不留候选态。
     *
     * <p>建之前查一次同 target 的待审批：agent 反复改同一条边（改 confidence、改 join
     * 表达式）会一路走到这里，不查就是同一条边在队列里排好几遍。
     */
    private MutationResult queueCandidateReview(String alias, GraphWorkspace workspace,
            GraphActor actor, MutationResult result) {
        String targetId = result.getTargetId();
        if (!result.isSuccess() || result.getChangeId() == null || targetId == null
                || !targetId.startsWith("relation:")) {
            return result;
        }
        if (!(GraphObjectPatch.read(workspace, targetId) instanceof RelationWorkspaceEdge relation)
                || relation.getStatus() != GraphStatus.candidate) {
            return result;
        }
        if (runState.hasPendingApproval(alias, ApprovalGate.Kind.GRAPH.code(), targetId)) {
            return result;
        }
        long id = approvalGate.request(alias, ApprovalGate.Kind.GRAPH,
                describeCandidate(relation), relation.getJoinExpression(), null, targetId,
                GraphChangePayload.publish(targetId, actor == null ? null : actor.name(),
                        workspace.getManifest().getRevision()).toJson());
        return result.withPendingApproval(id);
    }

    /**
     * 把图谱里已有的候选边补进待审批队列，返回补了几条。
     *
     * <p>候选边改成排队评审之前写进去的那些，谁也没给它们建过审批行——不补的话
     * 它们就永远躺在图谱里，既不在待审批里、也没有别的地方能处理（候选队列已经从
     * 图谱页撤掉了）。{@code hasPendingApproval} 保证重复调用不会排两遍，
     * 所以每次开 UI 都跑一遍就够了，不需要额外的「迁移完成」标记。
     *
     * <p>读不到图谱就返回 0：新数据源还没导入图谱是正常状态，不该让 UI 起不来。
     */
    public int syncCandidateApprovals(String alias) {
        GraphWorkspace workspace;
        try {
            if (!store.exists(alias)) return 0;
            workspace = store.load(alias);
        } catch (Exception e) {
            return 0;
        }
        int queued = 0;
        for (RelationWorkspaceEdge relation : workspace.getRelations()) {
            if (relation.getStatus() != GraphStatus.candidate) continue;
            if (runState.hasPendingApproval(alias, ApprovalGate.Kind.GRAPH.code(), relation.getId())) {
                continue;
            }
            approvalGate.request(alias, ApprovalGate.Kind.GRAPH, describeCandidate(relation),
                    relation.getJoinExpression(), null, relation.getId(),
                    GraphChangePayload.publish(relation.getId(),
                            relation.getUpdatedBy() == null ? null : relation.getUpdatedBy().name(),
                            workspace.getManifest().getRevision()).toJson());
            queued++;
        }
        // 候选血缘同一条路：不排的话它永远是候选（auto 别名上写的都在这）。
        // 已经有人批准过的（批准即评审那步以前漏了血缘）不再排第二次，直接补定级——
        // 那是记账，不是新的内容主张，走 system 不过审批。
        List<String> alreadyApproved = new ArrayList<>();
        for (LineageRecord record : workspace.getLineage().values()) {
            if (record.getStatus() != GraphStatus.candidate) continue;
            if (runState.hasPendingApproval(alias, ApprovalGate.Kind.GRAPH.code(), record.getId())) {
                continue;
            }
            if (runState.hasApprovedApproval(alias, ApprovalGate.Kind.GRAPH.code(), record.getId())) {
                alreadyApproved.add(record.getId());
                continue;
            }
            approvalGate.request(alias, ApprovalGate.Kind.GRAPH, describeCandidate(record),
                    record.getExpression(), null, record.getId(),
                    GraphChangePayload.publish(record.getId(),
                            record.getUpdatedBy() == null ? null : record.getUpdatedBy().name(),
                            workspace.getManifest().getRevision()).toJson());
            queued++;
        }
        if (!alreadyApproved.isEmpty()) {
            mutate(alias, workspace.getManifest().getRevision(), GraphActor.system,
                    "候选血缘已在审批中批准，补定级", alreadyApproved.get(0), ApprovalMode.NEVER, current -> {
                        for (String id : alreadyApproved) {
                            LineageRecord record = current.getLineage().get(id);
                            if (record != null && record.getStatus() == GraphStatus.candidate) grade(record);
                        }
                        return new MutationOutcome(alreadyApproved.get(0), ChangeOperation.update,
                                alreadyApproved.subList(1, alreadyApproved.size()));
                    });
        }
        return queued;
    }

    /** 血缘那一行：类别、目标列、几个源、代码位置——够决定要不要发布了。 */
    private static String describeCandidate(LineageRecord record) {
        String kind = record.getLineageKind() == null ? "" : record.getLineageKind() + " ";
        return "发布候选血缘 " + kind + shortRef(record.getTarget()) + " ← "
                + record.getSources().size() + " 个源"
                + (record.getThrough() == null ? "" : "（" + record.getThrough() + "）");
    }

    /** 审批列表里那一行摘要：类型、两个端点、置信度——够决定要不要发布了。 */
    private static String describeCandidate(RelationWorkspaceEdge relation) {
        String confidence = relation.getConfidence() == null ? ""
                : "（置信度 " + Math.round(relation.getConfidence() * 100) + "%）";
        return "发布候选关系 " + relation.getType() + "：" + shortRef(relation.getFrom())
                + " → " + shortRef(relation.getTo()) + confidence;
    }

    /** `column:demo:APP.ORDERS.USER_ID` → `ORDERS.USER_ID`。 */
    private static String shortRef(String id) {
        if (id == null) return "?";
        String last = id.substring(id.lastIndexOf(':') + 1);
        String[] parts = last.split("\\.");
        return parts.length >= 3
                ? parts[parts.length - 2] + "." + parts[parts.length - 1]
                : last;
    }

    private static JsonNode snapshot(Object value) {
        return value == null ? null : GraphChangePayload.MAPPER.valueToTree(value);
    }

    /**
     * 「这两个快照是不是同一个东西」，其中「对象不存在」有两种写法。
     *
     * <p>Java 的 null 序列化进 payload 是 JSON 的 {@code null}，读回来是 {@link JsonNode} 的
     * NullNode 而不是 null。新增变更的 {@code before} 正是这种情况，用 {@code Objects.equals}
     * 直接比会把「本来就不存在」判成冲突——每一条新增都批不下去。
     */
    private static boolean sameNode(JsonNode a, JsonNode b) {
        JsonNode left = a == null || a.isNull() ? null : a;
        JsonNode right = b == null || b.isNull() ? null : b;
        return java.util.Objects.equals(left, right);
    }

    /**
     * 这次变更**实际动了哪几个字段**。冲突检测和写回都只看这个集合。
     *
     * <p>整对象比对会把「同一个对象上并发改了两个不相干的字段」判成冲突——审批 #154
     * 就是这么挂的：{@code value-domain} 只改 valueHints，中间有人写了 description，
     * 批准时 before 整体对不上，一条本来毫无矛盾的变更批不下去。
     *
     * <p>它还是批次能成立的前提：一批里的每一条都是基于**提交时**那份图谱算出来的，
     * 逐条按整对象写回会让后一条把前一条抹掉；只写自己动过的字段就天然可叠加。
     *
     * @return null 表示这次是整对象语义（新增或删除），没有字段集合可算
     */
    static java.util.Set<String> changedFields(JsonNode before, JsonNode after) {
        if (before == null || !before.isObject() || after == null || !after.isObject()) return null;
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        before.fieldNames().forEachRemaining(names::add);
        after.fieldNames().forEachRemaining(names::add);
        java.util.Set<String> changed = new java.util.LinkedHashSet<>();
        for (String name : names) {
            if (!sameNode(before.get(name), after.get(name))) changed.add(name);
        }
        return changed;
    }

    /**
     * 把 after 里**这几个字段**盖到当前对象上，其余字段保持现状。
     *
     * <p>不是整份 after 写回去：after 是提交那一刻的快照，整份写回等于把提交之后
     * 别人改的其他字段一起抹掉——那正是字段级判据要消掉的那类事故。
     */
    static JsonNode mergeFields(JsonNode current, JsonNode after, java.util.Set<String> fields) {
        com.fasterxml.jackson.databind.node.ObjectNode merged =
                current != null && current.isObject()
                        ? ((com.fasterxml.jackson.databind.node.ObjectNode) current).deepCopy()
                        : GraphChangePayload.MAPPER.createObjectNode();
        for (String field : fields) {
            JsonNode value = after.get(field);
            if (value == null) {
                merged.remove(field);
            } else {
                merged.set(field, value);
            }
        }
        return merged;
    }

    /**
     * 一次动了多个对象时，把它们放进**一个批次**再进队列。
     *
     * <p>不这么做的话，要么只暂存主对象（其余的悄悄丢掉），要么散成 N 条互不相干的审批——
     * 后者更糟：人可能只批准了术语、没批准它的映射边，落地出来是个残缺状态。
     * 一批一次 load/apply/save，要么全进要么图谱一个字节没动。
     */
    /** 批次卡标题里那半句「改了什么」：主对象名 + 这批一共几个对象。 */
    private static String batchIntentSuffix(List<GraphChangePayload> payloads) {
        String primary = payloads.get(0).targetId();
        int cut = primary.lastIndexOf(':');
        String name = cut >= 0 && cut + 1 < primary.length() ? primary.substring(cut + 1) : primary;
        return name + "（共 " + payloads.size() + " 个对象）";
    }

    /** 队列里挂着的、这次提交也会碰到的同对象审批。 */
    private List<ApprovalRow> pendingGraphApprovals(String alias, List<GraphChangePayload> payloads) {
        Set<String> targets = new LinkedHashSet<>();
        for (GraphChangePayload payload : payloads) targets.add(payload.targetId());
        List<ApprovalRow> hits = new ArrayList<>();
        for (ApprovalRow row : runState.listApprovals(alias, ApprovalRow.STATUS_PENDING,
                ApprovalGate.Kind.GRAPH.code(), null, PENDING_LOOKUP_LIMIT, 0)) {
            if (row.targetId() != null && targets.contains(row.targetId())) hits.add(row);
        }
        return hits;
    }

    /**
     * 这次提交的每个对象，队列里是不是都已经有一份内容完全相同的。
     *
     * <p>比的是 {@code after}，不是整条 payload：{@code baseRevision} 每次都不一样，
     * 拿整条比等于永远不相等，这条短路就白写了。
     */
    private boolean allAlreadyQueued(List<GraphChangePayload> payloads, List<ApprovalRow> queued) {
        for (GraphChangePayload payload : payloads) {
            boolean matched = false;
            for (ApprovalRow row : queued) {
                if (!payload.targetId().equals(row.targetId())) continue;
                if (sameNode(payload.after(), parseAfter(row.payload()))) {
                    matched = true;
                    break;
                }
            }
            if (!matched) return false;
        }
        return true;
    }

    private JsonNode parseAfter(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) return null;
        try {
            JsonNode after = hashMapper.readTree(payloadJson).get("after");
            return after == null || after.isNull() ? null : after;
        } catch (Exception e) {
            // 读不懂的老 payload 当成「不一样」：宁可多排一条，也不能把一次真的改动吞掉
            log.warn("failed to parse queued approval payload: {}", e.toString());
            return null;
        }
    }

    /**
     * 让被取代的那份作废。
     *
     * <p>复用 {@code expired}（UI 上是「已作废」）而不是新加一个状态：图谱审批不阻塞、
     * 从来不会因为等待超时走到 expired，这个值在图谱这条路上是空的，语义也对得上。
     * 区分靠 {@code reason}——它会写明是被哪次提交取代的。
     *
     * <p>批次里的条目被抽空之后批次本身也要收尾，否则「待审批」角标上会挂一个
     * 点进去什么都没有的批次。
     */
    private void supersede(String alias, List<ApprovalRow> queued, List<GraphChangePayload> payloads) {
        if (queued.isEmpty()) return;
        Set<String> targets = new LinkedHashSet<>();
        for (GraphChangePayload payload : payloads) targets.add(payload.targetId());
        Set<Long> batches = new LinkedHashSet<>();
        for (ApprovalRow row : queued) {
            if (!targets.contains(row.targetId())) continue;
            runState.decideApproval(row.id(), ApprovalRow.STATUS_EXPIRED,
                    "已被同一对象的新提交取代（原内容未生效，图谱一个字节没动）");
            if (row.batchId() != null) batches.add(row.batchId());
        }
        for (Long batchId : batches) {
            boolean stillLive = runState.listBatchItems(batchId).stream()
                    .anyMatch(item -> ApprovalRow.STATUS_PENDING.equals(item.status()));
            if (!stillLive) {
                runState.finishBatch(batchId, ApprovalBatchRow.STATUS_SUPERSEDED, null,
                        "整批已被同一对象的新提交取代");
            }
        }
    }

    private MutationResult stageBatch(String alias, String summary, String reason,
            List<GraphChangePayload> payloads, String targetId, long baseRevision) {
        // intent 带上对象名。评审页的批次卡标题就是 intent，而自动建的批次拿命令名当
        // intent 时，同一条命令产出的每一批标题都一模一样——真实事故里队列上并排三张
        // 「schema add-term」，条数都是 3，肉眼分不出哪张是哪张。
        long batchId = runState.createBatch(alias, ApprovalBatchRow.KIND_GRAPH,
                summary + "：" + batchIntentSuffix(payloads));
        if (batchId == 0L) {
            return MutationResult.failure("批次建不出来，这次变更没有提交（运行库是否可写）");
        }
        Long first = null;
        for (GraphChangePayload payload : payloads) {
            long id = runState.createApproval(alias, ApprovalGate.Kind.GRAPH.code(), summary, reason,
                    null, payload.targetId(), payload.toJson(),
                    ApprovalRow.STATUS_DRAFT, batchId, runState.nextBatchSeq(batchId));
            if (first == null) first = id;
        }
        // 建完立刻 submit：这批不是人手动攒的草稿，是一条命令的完整产物，
        // 留在 draft 等于提交了却不在任何队列里
        runState.submitBatch(batchId, true);
        return MutationResult.pending(first == null ? batchId : first, targetId, baseRevision);
    }

    /**
     * 批准一条待审批的图谱变更：把 payload 重放到当时最新的图谱上。
     *
     * <p>重放前比对当前值和 {@link GraphChangePayload#before}：不相等说明提交之后有人动过
     * 这个对象，直接写会把那次改动盖掉，所以拦下来让提交方基于最新图谱重来。
     *
     * <p>批准就是评审本身——重放出来的关系如果还是候选态，顺手按置信度定级发布，
     * 否则人要为同一条边点两次「同意」。
     */
    public MutationResult decideGraphApproval(String alias, long approvalId,
            GraphChangePayload payload, boolean approved, String reason) {
        if (payload == null) {
            return MutationResult.failure("这条审批没有可重放的变更内容（升级前的老记录）");
        }
        if (payload.isPolicy()) {
            // 规则不在图谱里：批准 = 写进规则文件并绑定，拒绝 = 什么都没发生
            if (!approved) return MutationResult.success(0, null, payload.targetId());
            try {
                new com.sqlcli.graph.policy.PolicyRuleProposals(store).apply(alias, payload);
                return MutationResult.success(0, null, payload.targetId());
            } catch (Exception e) {
                return MutationResult.failure("规则写入失败：" + e.getMessage());
            }
        }
        if (payload.isPublish()) {
            // 对象已经在图谱里，裁决改的是它的状态而不是内容；那条边在写锁里重读，
            // 图谱这期间被别人推进了几版都不影响这次判断
            String note = "审批 #" + approvalId + (reason == null || reason.isBlank() ? "" : "：" + reason);
            if (!approved && !targetStillExists(alias, payload.targetId())) {
                // 拒绝的意思是「这条不要」。对象已经不在图谱里了（工作区被删、边被清理、
                // 别名换了图谱），这个意图早就达成，没有什么可做的。
                //
                // **不能因此让拒绝失败**：那条审批会永远卡在队列里，批也批不了、拒也拒不掉。
                // 真实撞见过——一次 e2e 测试往运行库写了 4 条 publish 审批，测完把工作区删了。
                return MutationResult.success(0, null, payload.targetId());
            }
            if (payload.targetId().startsWith("lineage:")) {
                return approved
                        ? publishLineage(alias, payload.targetId(), ANY_REVISION, note)
                        : rejectLineage(alias, payload.targetId(), ANY_REVISION, note);
            }
            return approved
                    ? publishRelation(alias, payload.targetId(), ANY_REVISION, note)
                    : rejectRelation(alias, payload.targetId(), ANY_REVISION, note);
        }
        if (!approved) {
            // 变更从来没进过图谱，拒绝不需要任何图谱侧动作
            return MutationResult.success(0, null, payload.targetId());
        }
        return applyApproved(alias, approvalId, payload);
    }

    /**
     * 这个对象还在图谱里吗。工作区读不到（被删、别名换了图谱）一律当不在。
     *
     * <p>只给**拒绝**用。批准仍然必须硬失败——往一个不存在的图谱里发布，报成功就是骗人。
     */
    private boolean targetStillExists(String alias, String targetId) {
        try {
            return GraphObjectPatch.read(store.load(alias), targetId) != null;
        } catch (Exception e) {
            return false;
        }
    }

    public MutationResult applyApproved(String alias, long approvalId, GraphChangePayload payload) {
        if (payload == null) {
            return MutationResult.failure("这条审批没有可重放的变更内容（升级前的老记录）");
        }
        String targetId = payload.targetId();
        if (!GraphObjectPatch.supports(targetId)) {
            return MutationResult.failure("无法定位的图谱对象: " + targetId);
        }
        ChangeOperation operation;
        try {
            operation = ChangeOperation.valueOf(payload.operation());
        } catch (RuntimeException e) {
            operation = ChangeOperation.update;
        }
        ChangeOperation finalOperation = operation;
        // 不比对版本：冲突判据是 applyPatch 里的字段级 before 比对，它在写锁里做，
        // 比版本号准确得多。见 ANY_REVISION 的说明。
        return mutate(alias, ANY_REVISION, GraphActor.human,
                "审批 #" + approvalId + " 放行：" + payload.actor() + " 提交的变更",
                targetId, ApprovalMode.NEVER, approvalId, workspace -> {
            applyPatch(workspace, payload, null);
            return new MutationOutcome(targetId, finalOperation);
        });
    }

    /**
     * 把一条待批准的变更重放进工作区——**只碰它自己动过的字段**。
     *
     * <p>冲突判据同样是字段级：只要求这几个字段的当前值仍等于 {@code before}。
     * 提交到批准之间别人改了同一对象的**其他**字段不算冲突，那正是 #154 的教训。
     *
     * @param written 同一批里前面的条目已经写过的「targetId#字段」；这些不算冲突——
     *                它们的新值就是这一批自己刚写进去的，拿它跟 before 比对没有意义。
     *                单条审批传 null。
     */
    static void applyPatch(GraphWorkspace workspace, GraphChangePayload payload,
            java.util.Set<String> written) throws Exception {
        String targetId = payload.targetId();
        Class<?> type = GraphObjectPatch.kindOf(targetId).type();
        JsonNode current = snapshot(GraphObjectPatch.read(workspace, targetId));
        java.util.Set<String> fields = changedFields(payload.before(), payload.after());
        Object value;
        if (fields == null) {
            // 新增或删除：没有「只改了哪几个字段」可言，整对象比对
            if (!sameNode(current, payload.before()) && !sameNode(current, payload.after())) {
                throw conflict(targetId, null);
            }
            // after 是 JSON null（删除）时 treeToValue 返回 null，GraphObjectPatch.write 据此删对象
            value = GraphChangePayload.MAPPER.treeToValue(payload.after(), type);
        } else {
            if (current == null || current.isNull()) {
                throw new IllegalStateException("该对象在提交审批之后已被删除（" + targetId
                        + "），这条变更无处可落。请让提交方基于最新图谱重新提交。");
            }
            for (String field : fields) {
                if (written != null && written.contains(targetId + "#" + field)) continue;
                JsonNode now = current.get(field);
                // 当前值已经就是要写的那个值 = 别人跟这次变更想到一块去了，不是冲突。
                // 少了这一条，每次列编辑都无条件写的 verified / confidence 会把任意两条
                // 针对同一列的变更判成互斥——它们写的是同一个值。
                if (sameNode(now, payload.before().get(field))
                        || sameNode(now, payload.after().get(field))) {
                    continue;
                }
                throw conflict(targetId, field);
            }
            value = GraphChangePayload.MAPPER.treeToValue(
                    mergeFields(current, payload.after(), fields), type);
            if (written != null) {
                for (String field : fields) written.add(targetId + "#" + field);
            }
        }
        GraphObjectPatch.write(workspace, targetId, value);
        publishIfCandidate(workspace, targetId);
    }

    /** 一批里的一条：哪条审批、要重放什么。 */
    public record BatchItem(long approvalId, GraphChangePayload payload) {
    }

    /**
     * 一批图谱变更**原子落地**：一次拿写锁、load 一次、逐条重放、校验一次、save 一次。
     *
     * <p>要么全进，要么图谱一个字节没动。逐条走 {@link #applyApproved} 做不到这件事——
     * 那是 N 次独立的 load/save，第 12 条失败时前 11 条已经在图谱里了，
     * 而「批准这一批」的语义里没有「批准了一半」。
     *
     * <p>{@code written} 让同一批里前面的条目不被后面的条目判成冲突：一批中的每一条
     * 都是基于**提交时**那份图谱算出来的，第一条落下去之后第二条看到的当前值当然变了。
     * 那不是并发冲突，是这一批自己造成的。
     *
     * @param items 按 seq 排好、且都还没被单独否掉的条目
     */
    public MutationResult applyBatch(String alias, long batchId, List<BatchItem> items) {
        if (items == null || items.isEmpty()) {
            return MutationResult.failure("批次 #" + batchId + " 里没有可落地的条目");
        }
        for (BatchItem item : items) {
            if (item.payload() == null) {
                return MutationResult.failure("条目 #" + item.approvalId()
                        + " 没有可重放的变更内容（升级前的老记录），整批未落地");
            }
            if (!GraphObjectPatch.supports(item.payload().targetId())) {
                return MutationResult.failure("条目 #" + item.approvalId() + " 指向的对象无法定位（"
                        + item.payload().targetId() + "），整批未落地");
            }
        }
        try (WriteLockGuard lock = acquireWriteLock(alias)) {
            GraphWorkspace workspace = store.load(alias);
            String beforeHash = hashWorkspace(workspace);
            java.util.Set<String> written = new java.util.HashSet<>();
            for (BatchItem item : items) {
                try {
                    applyPatch(workspace, item.payload(), written);
                } catch (Exception e) {
                    return MutationResult.failure("条目 #" + item.approvalId() + "：" + e.getMessage()
                            + "（整批未落地）");
                }
            }
            return commitBatch(alias, workspace, beforeHash, batchId, items);
        } catch (WorkspaceLockException e) {
            return MutationResult.failure("lock error: " + e.getMessage());
        } catch (Exception e) {
            return MutationResult.failure("save error: " + e.getMessage());
        }
    }

    /** 一次 save、N 条变更流水。批次里的每一条都要能单独在流水里查到。 */
    private MutationResult commitBatch(String alias, GraphWorkspace workspace, String beforeHash,
            long batchId, List<BatchItem> items) throws Exception {
        String afterHash = hashWorkspace(workspace);
        if (beforeHash.equals(afterHash)) {
            return MutationResult.success(workspace.getManifest().getRevision(), null, null);
        }
        List<String> errors = validateWorkspace(workspace);
        if (!errors.isEmpty()) return MutationResult.failure(errors);
        String reason = "批次 #" + batchId + " 放行";
        long revisionBefore = workspace.getManifest().getRevision();
        String changeId = null;
        for (BatchItem item : items) {
            ChangeRecord change = ChangeRecord.create(alias, operationOf(item.payload()),
                    item.payload().targetId(), GraphActor.human);
            change.setReason(reason);
            change.setBeforeHash(beforeHash);
            change.setAfterHash(afterHash);
            workspace.getChanges().add(change);
            changeId = change.getId();
        }
        workspace.getManifest().incrementRevision();
        store.save(workspace);
        long revisionAfter = workspace.getManifest().getRevision();
        for (BatchItem item : items) {
            MutationOutcome outcome = new MutationOutcome(item.payload().targetId(),
                    operationOf(item.payload()));
            runState.recordGraphChange(alias, outcome.operation().name(), outcome.targetId(),
                    GraphActor.human.name(), revisionBefore, revisionAfter,
                    changeAudit(outcome, GraphActor.human, revisionBefore, workspace),
                    item.approvalId(), reason);
        }
        return MutationResult.success(revisionAfter, changeId, null);
    }

    private static ChangeOperation operationOf(GraphChangePayload payload) {
        try {
            return ChangeOperation.valueOf(payload.operation());
        } catch (RuntimeException e) {
            return ChangeOperation.update;
        }
    }

    /** 冲突要说清是哪个字段——「这个对象被改过了」逼人自己去 diff 整个对象。 */
    private static IllegalStateException conflict(String targetId, String field) {
        return new IllegalStateException("该对象在提交审批之后已被改动（" + targetId
                + (field == null ? "" : " 的 " + field)
                + "），批准会覆盖那次改动。请让提交方基于最新图谱重新提交。");
    }

    /**
     * 批准即评审：候选关系 / 候选血缘落地时直接定级，不再要求人在图谱页点第二次「发布」。
     *
     * <p>血缘原来漏在这里：批准只重放了记录，状态还是 candidate，而 candidate 又没有别的出口——
     * 17 条全挂着「待发布」，这个状态就没有意义了。判据与关系同一条：置信度 ≥ 0.9 算 verified。
     */
    private static void publishIfCandidate(GraphWorkspace workspace, String targetId) {
        Object object = GraphObjectPatch.read(workspace, targetId);
        if (!(object instanceof BaseGraphObject candidate)) return;
        if (!(object instanceof RelationWorkspaceEdge) && !(object instanceof LineageRecord)) return;
        if (candidate.getStatus() != GraphStatus.candidate) return;
        grade(candidate);
    }

    /** 候选定级：置信度 ≥ 0.9 升为 verified，否则 partial（保留，不再是候选）。关系和血缘同一份判据。 */
    private static void grade(BaseGraphObject candidate) {
        Double confidence = candidate.getConfidence();
        boolean verified = confidence != null && confidence >= RelationValidator.VERIFIED_MIN_CONFIDENCE;
        candidate.setVerified(verified);
        candidate.setStatus(verified ? GraphStatus.verified : GraphStatus.partial);
    }

    @FunctionalInterface
    public interface WorkspaceMutation {
        MutationOutcome apply(GraphWorkspace workspace) throws Exception;
    }

    /**
     * 一次变更动了哪些对象。
     *
     * @param alsoChanged 除主对象外还动了的对象 id。**一次动多个对象时必须声明**——
     *                    暂存待审批走的是单对象补丁，没声明的对象不会进 payload，
     *                    批准时也就不会被重放。{@code add-term --map} 就踩过：
     *                    术语进了审批、11 条 term_mapping 边一条都没进，批准之后边全丢了；
     *                    而且术语本身没变时连审批都不建，直接报「没有修改」，
     *                    用 CLI 再也提交不进去。
     */
    public record MutationOutcome(String targetId, ChangeOperation operation,
            List<String> alsoChanged) {

        public MutationOutcome(String targetId, ChangeOperation operation) {
            this(targetId, operation, List.of());
        }

        /** 主对象排在前面：单对象时它就是唯一那条，多对象时批次里它是第一条。 */
        List<String> allTargets() {
            List<String> all = new ArrayList<>();
            all.add(targetId);
            for (String id : alsoChanged) if (!all.contains(id)) all.add(id);
            return all;
        }
    }

    /**
     * 更新表描述信息。
     *
     * @param alias           数据库别名
     * @param tableId         表 ID
     * @param patch           更新字段（可包含: description, businessName, tags）
     * @param expectedRevision 乐观锁期望的 revision
     * @param reason          变更原因
     * @return MutationResult
     */
    public MutationResult updateTableDescription(String alias, String tableId,
            Map<String, Object> patch, long expectedRevision, String reason) {
        return mutate(alias, expectedRevision, GraphActor.human, reason, workspace -> {
            TableWorkspaceNode table = workspace.getTables().get(tableId);
            if (table == null) {
                throw new IllegalArgumentException("table not found: " + tableId);
            }
            applyTablePatch(table, patch);
            return new MutationOutcome(tableId, ChangeOperation.update);
        });
    }

    /**
     * 更新列描述信息。
     *
     * @param alias           数据库别名
     * @param tableId         表 ID
     * @param columnName      列名
     * @param patch           更新字段（可包含: businessName, semanticType）
     * @param expectedRevision 乐观锁期望的 revision
     * @param reason          变更原因
     * @return MutationResult
     */
    public MutationResult updateColumnDescription(String alias, String tableId, String columnName,
            Map<String, Object> patch, long expectedRevision, String reason) {
        return mutate(alias, expectedRevision, GraphActor.human, reason, workspace -> {
            TableWorkspaceNode table = workspace.getTables().get(tableId);
            if (table == null) {
                throw new IllegalArgumentException("table not found: " + tableId);
            }
            ColumnWorkspaceNode column = table.findColumn(columnName);
            if (column == null) {
                throw new IllegalArgumentException("column not found: " + columnName + " in table " + tableId);
            }
            applyColumnPatch(column, patch);
            String columnId = column.computeId(alias, table.getSchema(), table.getName());
            return new MutationOutcome(columnId, ChangeOperation.update);
        });
    }

    /**
     * 添加关系。
     *
     * @param alias           数据库别名
     * @param type            关系类型
     * @param from            起始端点 ID
     * @param to              目标端点 ID
     * @param cardinality     基数
     * @param joinExpression  JOIN 表达式
     * @param confidence      置信度
     * @param verified        是否已验证
     * @param expectedRevision 乐观锁期望的 revision
     * @param reason          变更原因
     * @return MutationResult
     */
    public MutationResult addRelation(String alias, RelationType type, String from, String to,
            RelationCardinality cardinality, String joinExpression,
            Double confidence, Boolean verified,
            long expectedRevision, String reason) {
        return addRelation(alias, type, from, to, cardinality, joinExpression, confidence, verified,
                expectedRevision, reason, GraphActor.human);
    }

    /**
     * 添加关系（指定写入者）。agent/extractor 写入的关系初始状态为 candidate。
     */
    public MutationResult addRelation(String alias, RelationType type, String from, String to,
            RelationCardinality cardinality, String joinExpression,
            Double confidence, Boolean verified,
            long expectedRevision, String reason, GraphActor actor) {
        String relationId = GraphIds.relationId(alias, type, from, to);
        return mutate(alias, expectedRevision, actor, reason, relationId, workspace -> {
            // 端点校验
            GraphObjectKind fromKind = workspace.resolveNodeKind(from);
            GraphObjectKind toKind = workspace.resolveNodeKind(to);
            if (fromKind == null) {
                throw new IllegalArgumentException("from endpoint not found: " + from);
            }
            if (toKind == null) {
                throw new IllegalArgumentException("to endpoint not found: " + to);
            }

            // 关系类型端点规则校验；推断类关系缺 confidence 时 effectiveConfidence 为 null，校验会拒绝
            Double effectiveConfidence = confidence != null ? confidence : type.defaultConfidence();
            RelationValidator.ValidationResult vr = relationValidator.validate(
                    type, fromKind, toKind, effectiveConfidence, verified);
            if (!vr.isValid()) {
                throw new IllegalArgumentException(String.join("; ", vr.getErrors()));
            }

            // 检查是否允许用户创建此类型关系
            if (!relationValidator.isUserCreatable(type)) {
                throw new IllegalArgumentException("relation type " + type + " cannot be created by user");
            }

            RelationWorkspaceEdge edge = RelationWorkspaceEdge.create(alias, type, from, to, actor);
            if (findRelation(workspace, edge.getId()) != null) {
                throw new IllegalArgumentException("relation already exists: " + edge.getId());
            }
            if (cardinality != null) {
                edge.setCardinality(cardinality);
            }
            if (joinExpression != null) {
                edge.setJoinExpression(joinExpression);
            }
            edge.setConfidence(effectiveConfidence);
            if (verified != null) {
                edge.setVerified(verified);
            }
            workspace.getRelations().add(edge);
            return new MutationOutcome(edge.getId(), ChangeOperation.create);
        });
    }

    /**
     * 更新关系。
     *
     * @param alias           数据库别名
     * @param relationId      关系 ID
     * @param updates         更新字段
     * @param expectedRevision 乐观锁期望的 revision
     * @param reason          变更原因
     * @return MutationResult
     */
    public MutationResult updateRelation(String alias, String relationId,
            Map<String, Object> updates, long expectedRevision, String reason) {
        return mutate(alias, expectedRevision, GraphActor.human, reason, relationId, workspace -> {
            RelationWorkspaceEdge relation = findRelation(workspace, relationId);
            if (relation == null) {
                throw new IllegalArgumentException("relation not found: " + relationId);
            }
            applyRelationPatch(relation, updates);
            return new MutationOutcome(relationId, ChangeOperation.update);
        });
    }

    /**
     * 发布候选关系：置信度 >= 0.9 时升为 verified，否则降级为 partial（保留但不再是候选）。
     */
    public MutationResult publishRelation(String alias, String relationId,
            long expectedRevision, String reason) {
        MutationResult result = mutate(alias, expectedRevision, GraphActor.human, reason, relationId,
                ApprovalMode.NEVER, workspace -> {
            RelationWorkspaceEdge relation = findRelation(workspace, relationId);
            if (relation == null) {
                throw new IllegalArgumentException("relation not found: " + relationId);
            }
            Double confidence = relation.getConfidence();
            boolean verified = confidence != null && confidence >= RelationValidator.VERIFIED_MIN_CONFIDENCE;
            relation.setVerified(verified);
            relation.setStatus(verified ? GraphStatus.verified : GraphStatus.partial);
            return new MutationOutcome(relationId, ChangeOperation.update);
        });
        return settleCandidateApproval(alias, relationId, result, "approved", reason);
    }

    /** 发布候选血缘：判据同 {@link #publishRelation}。 */
    public MutationResult publishLineage(String alias, String lineageId,
            long expectedRevision, String reason) {
        MutationResult result = mutate(alias, expectedRevision, GraphActor.human, reason, lineageId,
                ApprovalMode.NEVER, workspace -> {
            LineageRecord record = workspace.getLineage().get(lineageId);
            if (record == null) {
                throw new IllegalArgumentException("lineage not found: " + lineageId);
            }
            grade(record);
            return new MutationOutcome(lineageId, ChangeOperation.update);
        });
        return settleCandidateApproval(alias, lineageId, result, "approved", reason);
    }

    /** 拒绝候选血缘：转 ignored 留痕，不物理删——同 {@link #rejectRelation} 的理由。 */
    public MutationResult rejectLineage(String alias, String lineageId,
            long expectedRevision, String reason) {
        MutationResult result = mutate(alias, expectedRevision, GraphActor.human, reason, lineageId,
                ApprovalMode.NEVER, workspace -> {
            LineageRecord record = workspace.getLineage().get(lineageId);
            if (record == null) {
                throw new IllegalArgumentException("lineage not found: " + lineageId);
            }
            record.setStatus(GraphStatus.ignored);
            record.setVerified(false);
            if (reason != null && !reason.isBlank()) {
                record.getAttributes().put(REJECTION_REASON_ATTR, reason);
            }
            return new MutationOutcome(lineageId, ChangeOperation.update);
        });
        return settleCandidateApproval(alias, lineageId, result, "rejected", reason);
    }

    /**
     * 发布 / 拒绝候选边时，把排在待审批里的那条一并结掉。
     *
     * <p>这两个动作有两个触发点：审批中心裁决，或者代码直接调（批量评审、CLI）。
     * 后者不同步结掉，那条审批就永远 pending——一个已经处理完的数字挂在待审批角标上。
     */
    private MutationResult settleCandidateApproval(String alias, String relationId,
            MutationResult result, String decision, String reason) {
        if (result.isSuccess()) {
            runState.decidePendingApprovalsByTarget(alias, ApprovalGate.Kind.GRAPH.code(),
                    relationId, decision, reason);
        }
        return result;
    }

    /** 拒绝原因存放的 attributes 键：不新增模型字段，写法与第 1 条「复合外键分组」一致，旧图谱天然兼容。 */
    public static final String REJECTION_REASON_ATTR = "rejectionReason";

    /**
     * 拒绝候选关系：转为 {@link GraphStatus#ignored} 并记下拒绝原因，不再物理删除。
     *
     * <p>删除等于什么痕迹都不留——除了变更流水里一条 delete 记录，图谱里看不出这条边
     * 被人看过还是从没出现过，下次导入或候选挖掘会把它原样生成一遍，人得反复拒同一条候选。
     * {@code ignored} 是 {@link GraphStatus} 现成的一态，此前只有 {@link WorkspaceMetadataExtractor}
     * 用来标系统 schema；这里是它的第二个用途：标「人工确认过、不要」。
     * 生成侧接入 {@link #isIgnored} 后就不会再把同一条候选摆到人面前。
     */
    public MutationResult rejectRelation(String alias, String relationId,
            long expectedRevision, String reason) {
        MutationResult result = mutate(alias, expectedRevision, GraphActor.human, reason, relationId,
                ApprovalMode.NEVER, workspace -> {
            RelationWorkspaceEdge relation = findRelation(workspace, relationId);
            if (relation == null) {
                throw new IllegalArgumentException("relation not found: " + relationId);
            }
            // foreign_key 不会以候选身份出现在拒绝入口，但直接打 API 仍可能命中——
            // 与 deleteRelation 保持同一条防线，不能靠"拒绝"绕过"删除"的保护。
            if (relation.getType() == RelationType.foreign_key) {
                throw new IllegalArgumentException("cannot reject foreign_key relation: " + relationId);
            }
            relation.setStatus(GraphStatus.ignored);
            relation.getAttributes().put(REJECTION_REASON_ATTR,
                    reason == null || reason.isBlank() ? "拒绝候选关系" : reason);
            relation.touch(GraphActor.human);
            return new MutationOutcome(relationId, ChangeOperation.update);
        });
        return settleCandidateApproval(alias, relationId, result, "rejected", reason);
    }

    /**
     * 撤销忽略：把关系状态从 {@link GraphStatus#ignored} 改回 {@link GraphStatus#candidate}，
     * 供人反悔或候选证据更新后重新评审。清掉 {@link #REJECTION_REASON_ATTR}——
     * 一旦回到候选队列就是一次全新的评审，留着上次的拒绝理由只会误导下一次判断。
     */
    public MutationResult unignoreRelation(String alias, String relationId,
            long expectedRevision, String reason) {
        return mutate(alias, expectedRevision, GraphActor.human, reason, relationId,
                ApprovalMode.NEVER, workspace -> {
            RelationWorkspaceEdge relation = findRelation(workspace, relationId);
            if (relation == null) {
                throw new IllegalArgumentException("relation not found: " + relationId);
            }
            if (relation.getStatus() != GraphStatus.ignored) {
                throw new IllegalArgumentException("relation is not ignored: " + relationId);
            }
            relation.setStatus(GraphStatus.candidate);
            relation.getAttributes().remove(REJECTION_REASON_ATTR);
            relation.touch(GraphActor.human);
            return new MutationOutcome(relationId, ChangeOperation.update);
        });
    }

    /**
     * 候选生成方（导入的 FK 之外的推断、数据剖析挖掘、metric 建议……）在产出一条候选边之前
     * 应该先问这一句：这条边是不是已经被人工拒绝过。命中就不再产出，否则每次导入/挖掘都会
     * 把同一条边重新摆到人面前，{@link #rejectRelation} 留下的信号就形同虚设。
     *
     * <p>比对用的是 {@link GraphIds#relationId} 拼出的确定性 id（同一对端点 + 同一类型只有
     * 一条边），不看 confidence / joinExpression 这类易变字段——这些字段变了也还是"同一条边
     * 被拒绝过"，不构成重新产出的理由。
     *
     * <p>现在还没有调用方：候选生成分散在导入管线与未来的挖掘任务里，接入是那些代码自己的事，
     * 这里只提供判定本身。
     *
     * <h2>{@code GraphStatus.ignored} 的读法——全仓统一，别在别处重新推一遍</h2>
     * 拒绝改成保留边、转状态之后（见 {@link #rejectRelation}），{@code workspace.getRelations()}
     * 的每一处读者都得自己决定要不要把 ignored 边当"不存在"。三类读法，判据是
     * <b>这次读取要不要把结果喂回给人或喂给生成逻辑</b>：
     * <ul>
     *   <li><b>必须过滤</b>——一切"拿关系去派生/生成东西"的路径：路径查找、邻接查询（画布节点的
     *       关系数与孤立判定）、画布边、图表、规则评估（索引建议、join 类型匹配、字典关系）、
     *       未来的 SQL/JOIN 生成。判据：人已经明确否掉的判断，不能反过来影响产出——
     *       这正是这次改动要堵的口子（见类头改动记录）。</li>
     *   <li><b>必须不过滤</b>——持久化与结构性联动：落盘/读取（{@code hashWorkspace}、
     *       {@code findRelation} 这类按 id 精确定位）、export/import、merge、DROP TABLE 之类
     *       结构变更引发的级联清理（那是"这张表没了"，与 ignored 无关，两种状态的边都要清）。
     *       ignored 边必须能原样存取、原样搬运，否则下次导入把它当白纸重新生成一遍，
     *       "拒绝"这个动作的全部意义就没了。</li>
     *   <li><b>显式展示，不过滤</b>——给人看的地方：关系列表按 candidate/正式/已忽略三组渲染、
     *       评审页按 targetId 精确查详情。这里既不过滤也不是"忘了过滤"，是刻意让 ignored
     *       可见、可翻看、可撤销——过滤掉就等于把它藏起来，和物理删除没区别。</li>
     * </ul>
     * 落地方式不强求统一走一个共享谓词：过滤判断通常是 {@code status != GraphStatus.ignored}
     * 这样的一行代码，跨包引入一个工具方法换一行代码不值——但落哪一类、为什么，都要照这份表来，
     * 不要在调用点现推。
     */
    public static boolean isIgnored(GraphWorkspace workspace, String alias,
            RelationType type, String from, String to) {
        String candidateId = GraphIds.relationId(alias, type, from, to);
        for (RelationWorkspaceEdge edge : workspace.getRelations()) {
            if (edge.getId().equals(candidateId)) {
                return edge.getStatus() == GraphStatus.ignored;
            }
        }
        return false;
    }

    /**
     * 删除关系。禁止删除 foreign_key 类型。
     *
     * @param alias           数据库别名
     * @param relationId      关系 ID
     * @param expectedRevision 乐观锁期望的 revision
     * @param reason          变更原因
     * @return MutationResult
     */
    public MutationResult deleteRelation(String alias, String relationId,
            long expectedRevision, String reason) {
        return mutate(alias, expectedRevision, GraphActor.human, reason, relationId, workspace -> {
            RelationWorkspaceEdge relation = findRelation(workspace, relationId);
            if (relation == null) {
                throw new IllegalArgumentException("relation not found: " + relationId);
            }

            // 禁止删除 foreign_key
            if (relation.getType() == RelationType.foreign_key) {
                throw new IllegalArgumentException("cannot delete foreign_key relation: " + relationId);
            }
            workspace.getRelations().remove(relation);
            return new MutationOutcome(relationId, ChangeOperation.delete);
        });
    }

    /**
     * 删除一条血缘。血缘之间没有引用（源和目标都是列，不是别的血缘），删一条不牵连别的，
     * 所以不像术语那样级联。删除照 {@code after == null} 的补丁走审批重放，与关系、术语同一条路。
     *
     * @param actor CLI 上是 agent，UI 上是 human——变更流水和审批卡片按它区分是谁删的
     */
    public MutationResult deleteLineage(String alias, String lineageId,
            long expectedRevision, GraphActor actor, String reason) {
        return mutate(alias, expectedRevision, actor, reason, lineageId, workspace -> {
            if (workspace.getLineage().remove(lineageId) == null) {
                throw new IllegalArgumentException("lineage not found: " + lineageId);
            }
            return new MutationOutcome(lineageId, ChangeOperation.delete);
        });
    }

    /**
     * 删除一条术语，**连同挂在它身上的边一起删**。
     *
     * <p>留一条边就是一条 {@code dangling_relation_source} 校验错误，而让提交方先去
     * 关系列表里一条条找出来删掉——那是把一个机器一眼可见的引用关系推给人去查。
     *
     * <p>按 {@code from} / {@code to} 两头都收，不只看 {@code term_mapping}：判据是
     * 「这条边引用了这个术语吗」，不是「它是哪一类边」。{@code ignored} 状态的边同样要删，
     * 它一样在图谱里、一样会被校验器判成悬空。
     *
     * <p><b>曾经拒绝删是有理由的，那个理由已经不成立</b>：当时审批 payload 是单对象补丁，
     * 级联删边在 {@code manual} 别名上会变成「批准时只重放术语那一条，边留下来」。
     * 现在 {@link MutationOutcome#alsoChanged} 让一次变更产出多条补丁——多于一条时
     * {@link #stageForApproval} 自动开一个批次（每个对象一条），批准时
     * {@link #applyBatch} 原子重放。删除靠 {@code after == null} 的补丁表达，
     * 和其他删除走的是同一条路。
     */
    public MutationResult deleteTerm(String alias, String termId,
            long expectedRevision, String reason) {
        return mutate(alias, expectedRevision, GraphActor.human, reason, termId, workspace -> {
            if (!workspace.getTerms().containsKey(termId)) {
                throw new IllegalArgumentException("term not found: " + termId);
            }
            List<String> alsoDeleted = new ArrayList<>();
            workspace.getRelations().removeIf(edge -> {
                if (!termId.equals(edge.getFrom()) && !termId.equals(edge.getTo())) return false;
                alsoDeleted.add(edge.getId());
                return true;
            });
            workspace.getTerms().remove(termId);
            return new MutationOutcome(termId, ChangeOperation.delete, alsoDeleted);
        });
    }

    // ---- internal helpers ----

    private WriteLockGuard acquireWriteLock(String alias) throws WorkspaceLockException {
        try {
            return lockManager.acquireWriteLock(alias, store.workspacePath(alias), 30_000);
        } catch (java.io.IOException e) {
            throw new WorkspaceLockException(e.getMessage(), "workspace_path_invalid");
        }
    }

    /**
     * 「这次写入不做版本比对」。
     *
     * <p>乐观锁要成立，expectedRevision 必须是调用方**基于那一版做出决定**时读到的值——
     * UI 的编辑表单、CLI 的一次命令都是这样。裁决一条审批不是：批准动作本身不携带版本，
     * 真正的冲突判据在别处（apply 比对对象的 before，publish 在锁里重读那条边）。
     * 那时若临时读一次 revision 再传进来，读和拿锁之间隔着一个窗口，另一次裁决在这中间
     * 提交就报 "revision mismatch"——一个纯属自造、且没有任何保护作用的失败。
     */
    public static final long ANY_REVISION = -1;

    private void checkRevision(GraphWorkspace workspace, long expectedRevision) {
        if (expectedRevision == ANY_REVISION) return;
        long actual = workspace.getManifest().getRevision();
        if (actual != expectedRevision) {
            throw new WorkspaceLockException(
                    "revision mismatch: expected " + expectedRevision + " but was " + actual,
                    "revision_conflict");
        }
    }

    /**
     * @param approvalId 放行这次写入的审批；写进变更流水。null = 别名没开图谱审批，直接生效。
     */
    private MutationResult commitPrepared(String alias, GraphWorkspace workspace, String beforeHash,
            GraphActor actor, String reason, MutationOutcome outcome, Long approvalId) throws Exception {
        if (beforeHash.equals(hashWorkspace(workspace))) {
            return MutationResult.success(workspace.getManifest().getRevision(), null, outcome.targetId());
        }
        // Validation commits must persist the errors they just found; normal mutations still reject them.
        if (outcome.operation() != ChangeOperation.verify) {
            List<String> errors = validateWorkspace(workspace);
            if (!errors.isEmpty()) return MutationResult.failure(errors);
        }
        String afterHash = hashWorkspace(workspace);
        ChangeRecord change = ChangeRecord.create(alias, outcome.operation(), outcome.targetId(), actor);
        change.setReason(reason == null || reason.isBlank() ? "workspace mutation" : reason);
        change.setBeforeHash(beforeHash);
        change.setAfterHash(afterHash);
        workspace.getChanges().add(change);
        long revisionBefore = workspace.getManifest().getRevision();
        workspace.getManifest().incrementRevision();
        store.save(workspace);
        long revisionAfter = workspace.getManifest().getRevision();
        // 每次图谱更新都留一条带 before/after 的流水：光记「谁改了哪个 id」，
        // 回头问「改成了什么」只能去翻 YAML 目录的历史，而那个目录多半没进版本库。
        String audit = changeAudit(outcome, actor, revisionBefore, workspace);
        runState.recordGraphChange(alias, outcome.operation().name(), outcome.targetId(),
                actor == null ? null : actor.name(), revisionBefore, revisionAfter,
                audit, approvalId, reason);
        recordAutoApproved(alias, actor, reason, outcome, approvalId, audit);
        return MutationResult.success(revisionAfter, change.getId(), outcome.targetId());
    }

    /**
     * 别名是 {@code auto} 时补一条 status=approved 的审批条目。
     *
     * <p>auto 和 manual 是同一条管线的两个结尾，留底必须一样：不记的话
     * 「这条改动出自哪次会话、哪个 agent、为了什么」在 auto 别名上永远查不到，
     * 而那是绝大多数别名。它不进待审批队列——没有人裁决过的 pending 是假待办。
     *
     * <p>三种情况不记，它们都不是「有人主张的内容变更」：
     * {@code approvalId != null}（这次落盘本身就是在执行一条审批，再记一条是重复）、
     * {@link GraphActor#system} 的记账动作、targetId 不是单个图谱对象（整份快照导入）。
     *
     * <p>payload 只带 after，跟变更流水同一份——manual 条目的完整 before/after 是给
     * 「批之前得看 diff」用的，已经批过的东西没有那个需求，不值得为它多读一遍工作区。
     */
    private void recordAutoApproved(String alias, GraphActor actor, String reason,
            MutationOutcome outcome, Long approvalId, String audit) {
        if (approvalId != null || actor == GraphActor.system
                || !GraphObjectPatch.supports(outcome.targetId())) {
            return;
        }
        try {
            approvalGate.recordAutoApproved(alias, ApprovalGate.Kind.GRAPH,
                    reason == null || reason.isBlank() ? "图谱变更" : reason,
                    reason, outcome.targetId(), audit);
        } catch (RuntimeException e) {
            // 留底记不下来不能让一次已经落盘的变更报失败——变更流水里还有一条。
            log.debug("failed to record auto-approved graph item for {}", outcome.targetId(), e);
        }
    }

    /**
     * 变更流水里存的那份「改成了什么」。
     *
     * <p>只带 {@code after}，不带 {@code before}：拿 before 要在改动之前就知道 targetId，
     * 而 targetId 是 mutation 跑完才产出的，为它在每次写入前多读一遍整份工作区不划算。
     * 同一个 target 的上一条流水里的 after 就是这一条的 before；开了图谱审批的话，
     * 完整的 before/after 本来就在 {@code approval_request.payload} 里，
     * 顺着这条流水的 {@code approval_id} 就能查到。
     */
    private static String changeAudit(MutationOutcome outcome, GraphActor actor, long revisionBefore,
            GraphWorkspace workspace) {
        if (!GraphObjectPatch.supports(outcome.targetId())) return null;
        try {
            return new GraphChangePayload(outcome.targetId(), outcome.operation().name(),
                    actor == null ? null : actor.name(), revisionBefore, null,
                    snapshot(GraphObjectPatch.read(workspace, outcome.targetId())),
                    GraphChangePayload.ACTION_APPLY).toJson();
        } catch (RuntimeException e) {
            // 流水记不下来不能让一次已经落盘的变更报失败
            return null;
        }
    }

    private List<String> validateWorkspace(GraphWorkspace workspace) {
        validator.validate(workspace);
        List<String> errors = new ArrayList<>();
        for (ValidationIssueRecord issue : workspace.getValidationIssues()) {
            if (issue.getSeverity() == ValidationSeverity.error) {
                errors.add(issue.getCode() + ": " + issue.getMessage());
            }
        }
        return errors;
    }

    /**
     * 参与变更检测哈希时显式排除的 {@link GraphWorkspace} 字段——不是漏登记，是登记了「不算」。
     *
     * <ul>
     *   <li>{@code changes}——变更流水是 {@code commitPrepared} 自己的产物：本方法算出的
     *       afterHash 在 {@code commitPrepared} 里先算出来，之后才把新的 {@code ChangeRecord}
     *       追加进 {@code changes}（见该方法），追加动作本身永远发生在两次哈希之间的窗口
     *       之外。把它计入等于让哈希去比较"提交这个动作即将写的东西"，跟"工作区内容
     *       改没改"是两件事，也从没到过能影响判断的时间点。</li>
     *   <li>{@code manifest}——大多数字段是无条件的记账戳：{@code store.save()} 每次落盘都
     *       重算 {@code stats} 并 {@code touch()}（在哈希判断之后，不影响短路），但
     *       {@link GraphWorkspaceMerger#mergeBatch} 在合并里**无条件** {@code touch()}
     *       {@code updatedAt}——哪怕导入没扫到任何真实差异。整体纳入会让 no-op 短路在
     *       导入路径上永远失效。真正代表"内容变了"的只有 {@code lastValidationAt} /
     *       {@code lastIndexedAt} 这两个由具体动作（校验、建索引）写入的字段，单独取出来算。</li>
     * </ul>
     */
    private static final java.util.Set<String> HASH_EXCLUDED_FIELDS =
            java.util.Set.of("changes", "manifest");

    /**
     * 变更检测哈希：{@code commitPrepared} 用前后哈希是否相等来判断「什么都没改」而跳过落盘。
     *
     * <p>覆盖范围通过反射 {@link GraphWorkspace} 的实例字段自动生成，不再是手写清单——
     * 新增一个集合字段，不需要也没有地方可以同步登记，天然被覆盖，「忘了登记」这件事
     * 在结构上不存在了。手写清单曾经漏过 metrics 一次：{@code schema add-metric} 退出码
     * 0、提示成功，而 {@code schema metrics} 数出来是 0，就是因为那时候这里少写一行。
     * 排除项见 {@link #HASH_EXCLUDED_FIELDS} 上的说明，都是查清楚原因后才排除的，不是漏写。
     */
    private String hashWorkspace(GraphWorkspace workspace) throws Exception {
        Map<String, Object> state = new LinkedHashMap<>();
        for (java.lang.reflect.Field field : GraphWorkspace.class.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())
                    || HASH_EXCLUDED_FIELDS.contains(field.getName())) {
                continue;
            }
            field.setAccessible(true);
            state.put(field.getName(), field.get(workspace));
        }
        state.put("lastValidationAt", workspace.getManifest().getLastValidationAt());
        state.put("lastIndexedAt", workspace.getManifest().getLastIndexedAt());
        byte[] bytes = hashMapper.writeValueAsBytes(state);
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private RelationWorkspaceEdge findRelation(GraphWorkspace workspace, String relationId) {
        for (RelationWorkspaceEdge edge : workspace.getRelations()) {
            if (edge.getId().equals(relationId)) {
                return edge;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private void applyTablePatch(TableWorkspaceNode table, Map<String, Object> patch) {
        if (patch.containsKey("description")) {
            table.setDescription((String) patch.get("description"));
        }
        if (patch.containsKey("businessName")) {
            table.setBusinessName((String) patch.get("businessName"));
        }
        if (patch.containsKey("tags")) {
            table.setTags((List<String>) patch.get("tags"));
        }
        // 同 applyColumnPatch：人改过即确认。不动 status——那说的是「这张表存不存在」。
        table.setVerified(true);
        table.setConfidence(1.0);
    }

    /**
     * 字段只允许改业务描述、语义类型和值域。
     *
     * 注释来自数据库、类型和可空性是结构事实——它们由导入维护，改了下次导入就被冲掉，
     * 不如一开始就不给编辑入口（comment 不在这份白名单里，理由同上）。值域
     * （enumValues / format / sampleValues）是人和 Agent 维护的业务事实，与 CLI 的
     * {@code schema edit --enum-values} 写同一批字段。
     */
    @SuppressWarnings("unchecked")
    private void applyColumnPatch(ColumnWorkspaceNode column, Map<String, Object> patch) {
        if (patch.containsKey("description")) {
            column.setDescription((String) patch.get("description"));
        }
        if (patch.containsKey("businessName")) {
            column.setBusinessName((String) patch.get("businessName"));
        }
        // 值域是封闭集合，整体替换而不是追加：域变了要的是新的域，不是并集。空列表表示清空。
        if (patch.containsKey("enumValues")) {
            valueHints(column).setEnumValues(
                    ColumnValueHints.normalizeEnumValues(stringList(patch.get("enumValues"), "enumValues")));
        }
        if (patch.containsKey("format")) {
            Object raw = patch.get("format");
            String value = raw == null ? null : String.valueOf(raw);
            valueHints(column).setFormat(value == null || value.isBlank() ? null : value.trim());
        }
        if (patch.containsKey("sampleValues")) {
            valueHints(column).setSampleValues(
                    new ArrayList<>(stringList(patch.get("sampleValues"), "sampleValues")));
        }
        if (patch.containsKey("semanticType")) {
            Object raw = patch.get("semanticType");
            String value = raw == null ? null : String.valueOf(raw);
            if (value == null || value.isBlank()) {
                column.setSemanticType(null);
            } else {
                SemanticType parsed = SemanticType.fromValue(value);
                if (parsed == null) {
                    throw new IllegalArgumentException("unknown semanticType: " + value);
                }
                column.setSemanticType(parsed);
            }
        }
        // 人在 UI 里改这个字段，这一下就是确认动作本身——字段级没有候选态，
        // verified 位就是闸门（见 ColumnWorkspaceNode 类头）。不另做一个「确认」按钮：
        // 人看过、改过、保存了，比再点一次按钮更能说明他确认了。
        // 调用方 updateColumnDescription 的 actor 恒为 human，这里不需要再判一次。
        column.setVerified(true);
        column.setConfidence(1.0);
    }

    /** 按需建 valueHints：只有真写了值域的字段才带这个对象，其余字段保持 null 不落盘。 */
    private static ColumnValueHints valueHints(ColumnWorkspaceNode column) {
        if (column.getValueHints() == null) {
            column.setValueHints(new ColumnValueHints());
        }
        return column.getValueHints();
    }

    /** JSON 反序列化出来的列表项可能不是字符串（数字值域 [0,1]），统一转成 String。 */
    private static List<String> stringList(Object raw, String field) {
        if (!(raw instanceof List<?> list)) {
            throw new IllegalArgumentException("'" + field + "' must be a list");
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (item == null) continue;
            String value = String.valueOf(item).trim();
            if (!value.isEmpty()) result.add(value);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private void applyRelationPatch(RelationWorkspaceEdge relation, Map<String, Object> updates) {
        if (updates.containsKey("cardinality")) {
            Object val = updates.get("cardinality");
            if (val instanceof RelationCardinality rc) {
                relation.setCardinality(rc);
            } else if (val instanceof String s) {
                relation.setCardinality(RelationCardinality.valueOf(s));
            }
        }
        if (updates.containsKey("joinExpression")) {
            relation.setJoinExpression((String) updates.get("joinExpression"));
        }
        if (updates.containsKey("confidence")) {
            Object val = updates.get("confidence");
            if (val instanceof Number n) {
                relation.setConfidence(n.doubleValue());
            }
        }
        if (updates.containsKey("verified")) {
            Object val = updates.get("verified");
            if (val instanceof Boolean b) {
                relation.setVerified(b);
            }
        }
    }

    /**
     * 写操作结果。
     */
    @Getter
    public static class MutationResult {
        private final boolean success;
        private final long newRevision;
        private final String changeId;
        private final String targetId;
        private final List<String> errors;
        /** 非 null 表示变更已落库、但还挂着一条待人裁决的审批；见 {@link #withGraphApproval}。 */
        private final Long pendingApprovalId;

        private MutationResult(boolean success, long newRevision, String changeId, String targetId,
                List<String> errors, Long pendingApprovalId) {
            this.success = success;
            this.newRevision = newRevision;
            this.changeId = changeId;
            this.targetId = targetId;
            this.errors = errors != null ? errors : new ArrayList<>();
            this.pendingApprovalId = pendingApprovalId;
        }

        /**
         * 变更已经落盘，但它产出的候选还挂着一条待审批（见 {@link #queueCandidateReview}）。
         * 跟 {@link #pending} 的区别是 changeId 非空：那次写入是真发生了的。
         */
        public MutationResult withPendingApproval(long approvalId) {
            return new MutationResult(success, newRevision, changeId, targetId, errors, approvalId);
        }

        /**
         * 变更已存进审批队列、**图谱还没动**。
         *
         * <p>{@code changeId} 为 null、revision 不变：这两件事都还没发生，调用方据此不能
         * 报「图谱已更新」。它跟 no-op（同样 changeId=null）的区别是 pendingApprovalId 非空。
         */
        public static MutationResult pending(long approvalId, String targetId, long revision) {
            return new MutationResult(true, revision, null, targetId, new ArrayList<>(), approvalId);
        }

        public static MutationResult success(long newRevision, String changeId) {
            return new MutationResult(true, newRevision, changeId, null, new ArrayList<>(), null);
        }

        public static MutationResult success(long newRevision, String changeId, String targetId) {
            return new MutationResult(true, newRevision, changeId, targetId, new ArrayList<>(), null);
        }

        public static MutationResult failure(String error) {
            return new MutationResult(false, 0, null, null, new ArrayList<>(List.of(error)), null);
        }

        public static MutationResult failure(List<String> errors) {
            return new MutationResult(false, 0, null, null, errors, null);
        }
    }
}
