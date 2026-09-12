package com.sqlcli.profile;

import com.sqlcli.graph.workspace.GraphActor;
import com.sqlcli.graph.workspace.GraphIds;
import com.sqlcli.graph.workspace.GraphWorkspace;
import com.sqlcli.graph.workspace.RelationType;
import com.sqlcli.graph.workspace.RelationWorkspaceEdge;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 把 {@link TableProfiler} 实测的空值率喂给外键边的 optionality。
 *
 * <p><b>消费方是 {@code schema path} 的 INNER/LEFT 结论。</b>导入时只看 nullable 声明
 * （{@code optionalitySource=inferred}），声明可空的外键列一律给 LEFT JOIN——而实测
 * 空值率为 0 的那些，INNER 是安全的，LEFT 会多查出一批本该被过滤掉的行。反过来，
 * 声明非空但实测有空值时 INNER 会<b>静默丢行</b>：不报错、有结果、数偏少。
 *
 * <p><b>只算不写。</b>本类产出一串 {@link OptionalityUpgrade}，落地由调用方走
 * {@code WorkspaceMutationService.mutate}——图谱写入只有那一条管线，要不要审批由别名的
 * {@code graphApproval} 统一裁决，命令自己不许开小灶（CLAUDE.md「一个别名上只能有一种行为」）。
 *
 * <p><b>只做 optionality 这一件。</b>剖析原本还能产出值域候选与标注质量告警，两件都不在这里：
 * 值域已被 {@code schema value-domain} 的三源交叉取代（多一个代码枚举源，更准，且写图谱的
 * 路径只能有一条），标注质量告警的归宿是 {@code schema eval} 的 finding 表。
 */
public final class ProfileGraphWriter {

    private ProfileGraphWriter() {
    }

    /**
     * 一条待提交的 optionality 升级。
     *
     * @param reason 审批人看到的那句话——写清采样规模和空值率，不然队列里是一条无从判断的布尔值
     */
    public record OptionalityUpgrade(String relationId, String column, boolean fromOptional, String reason) {
    }

    /**
     * 算出这次剖析能给哪些外键边升级 optionality。只看 from 端。
     *
     * <p>to 端（父表主键行是否必然有子行）单表剖析回答不了，那是跨表的包含依赖问题，
     * 这里不碰 {@code toOptional}，维持导入时保守的初值（恒为 true，宁可多一次 LEFT JOIN）。
     */
    public static List<OptionalityUpgrade> planOptionalityUpgrades(
            GraphWorkspace workspace, String alias, TableProfile profile) {
        List<OptionalityUpgrade> upgrades = new ArrayList<>();
        for (ColumnProfile columnProfile : profile.columns()) {
            if (columnProfile.skipped() || columnProfile.rowsSampled() <= 0) {
                continue; // 没有可信的空值率，不产出结论
            }
            boolean measuredOptional = columnProfile.nullRatio() > 0.0;
            if (!measuredOptional && !sampleCoveredWholeTable(profile, columnProfile)) {
                continue; // 见 sampleCoveredWholeTable
            }
            String columnId = GraphIds.columnId(alias, profile.schemaName(), profile.tableName(),
                    columnProfile.columnName());
            for (RelationWorkspaceEdge edge : workspace.getRelations()) {
                if (edge.getType() != RelationType.foreign_key || !columnId.equals(edge.getFrom())
                        || !upgradable(edge, measuredOptional)) {
                    continue;
                }
                upgrades.add(new OptionalityUpgrade(edge.getId(), columnProfile.columnName(),
                        measuredOptional, reason(columnProfile, measuredOptional)));
            }
        }
        return upgrades;
    }

    /**
     * 落地一条升级——调用方在 {@code mutate} 回调里对<b>当时最新</b>的工作区调用，
     * 不在计划阶段那份上改（计划到落地之间别人可能已经改过图谱）。
     */
    public static void applyOptionality(GraphWorkspace workspace, OptionalityUpgrade upgrade) {
        RelationWorkspaceEdge edge = workspace.getRelations().stream()
                .filter(r -> upgrade.relationId().equals(r.getId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "relation not found: " + upgrade.relationId()));
        edge.getAttributes().put(RelationWorkspaceEdge.ATTR_FROM_OPTIONAL, upgrade.fromOptional());
        edge.getAttributes().put(RelationWorkspaceEdge.ATTR_OPTIONALITY_SOURCE,
                RelationWorkspaceEdge.OPTIONALITY_SOURCE_PROFILED);
        edge.touch(GraphActor.extractor);
    }

    /**
     * 「实测没有空值」只有在采样覆盖了整张表时才成立。
     *
     * <p>默认采样 10000 行（{@link ProfileOptions}）。拿一千万行表的一万行样本去断言
     * 「这一列没有 NULL」，然后据此把 LEFT 改成 INNER，正好制造出这个功能要防的那种错：
     * <b>不报错、有结果、数偏少</b>。样本里出现过 NULL 是另一回事——一个反例就足以证明
     * 这一列可空，所以只有 {@code measuredOptional=false} 这个方向需要这道闸。
     *
     * <p>行数拿不到（超时 / 权限）时同样不放行：不知道分母就不知道样本是不是全量。
     */
    private static boolean sampleCoveredWholeTable(TableProfile profile, ColumnProfile columnProfile) {
        return profile.rowCountAvailable() && columnProfile.rowsSampled() >= profile.rowCount();
    }

    private static boolean upgradable(RelationWorkspaceEdge edge, boolean measuredOptional) {
        Object source = edge.getAttributes().get(RelationWorkspaceEdge.ATTR_OPTIONALITY_SOURCE);
        boolean machineWritten = source == null
                || RelationWorkspaceEdge.OPTIONALITY_SOURCE_INFERRED.equals(source)
                || RelationWorkspaceEdge.OPTIONALITY_SOURCE_PROFILED.equals(source);
        if (!machineWritten) {
            // 出现别的取值就说明人表过态了（那个功能还没做，口子先挡住）：
            // 测量值不能倒回去盖掉人的判断，跟「不覆盖人写的值域」是同一个道理。
            return false;
        }
        // 已经是同一份实测结论：再提一条只是往审批队列里塞重复条目，
        // 而 value-domain 是会被反复跑的命令。
        return !(RelationWorkspaceEdge.OPTIONALITY_SOURCE_PROFILED.equals(source)
                && Objects.equals(edge.getFromOptional(), measuredOptional));
    }

    private static String reason(ColumnProfile columnProfile, boolean measuredOptional) {
        return String.format("外键可选性改按实测：%s 采样 %d 行、空值率 %.2f%%，%s",
                columnProfile.columnName(), columnProfile.rowsSampled(),
                columnProfile.nullRatio() * 100,
                measuredOptional ? "from 端可选（须 LEFT JOIN）" : "from 端不可选（INNER JOIN 安全）");
    }
}
