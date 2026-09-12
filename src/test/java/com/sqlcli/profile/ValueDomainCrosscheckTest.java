package com.sqlcli.profile;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 值域三源交叉。
 *
 * <p>注释解析那部分用的全是 `erp_plush_test` 里的真实注释——真实注释的写法是乱的，
 * 拿构造出来的漂亮样例测等于没测。
 *
 * <p>贯穿全部用例的一条：**宁可解析不出来，也不要解析错**。一条解析错的值域会变成
 * 一条错的发现，人对着错的发现做决定比没有发现更糟。
 */
class ValueDomainCrosscheckTest {

    private static ColumnProfile profile(String column, List<String> values, long distinct,
            boolean lowCardinality, long nonNull, double nullRatio) {
        return new ColumnProfile(column, false, null, 1000, nonNull, nullRatio,
                distinct, lowCardinality, values, null, null, null, null, 0);
    }

    // ---- 注释解析：三种真实写法 ----

    @Test
    void parsesEqualsSeparatedComment() {
        assertEquals(List.of("0=免扫码", "1=必须扫码"),
                ValueDomainCrosscheck.parseCommentEnum("是否扫码 0=免扫码,1=必须扫码"));
    }

    @Test
    void parsesSpaceSeparatedCommentWithoutEquals() {
        // erp_prop_plan_record_detal.state 的真实注释
        assertEquals(List.of("0=未开始", "1=一开始", "2=已完成", "3=已取消", "4=已超时", "5=已终止"),
                ValueDomainCrosscheck.parseCommentEnum(
                        "任务状态  0未开始 1一开始 2已完成 3已取消 4已超时 5已终止"));
    }

    @Test
    void parsesParenthesizedWeiForm() {
        // erp_exam_plan.exam_type 的真实注释
        assertEquals(List.of("0=线上计划", "1=线下计划"),
                ValueDomainCrosscheck.parseCommentEnum("计划类型(0为线上计划1为线下计划)"));
    }

    @Test
    void refusesToGuessFromOrdinaryProse() {
        // 只认出一组时不算值域声明——普通行文里的误伤比漏掉更糟
        assertTrue(ValueDomainCrosscheck.parseCommentEnum("扫码动作写入 scanPoint").isEmpty());
        assertTrue(ValueDomainCrosscheck.parseCommentEnum("点位信息").isEmpty());
        assertTrue(ValueDomainCrosscheck.parseCommentEnum(null).isEmpty());
    }

    // ---- 四种结论 ----

    @Test
    void databaseValueMissingFromCommentIsTheHardFinding() {
        // 注释说只有 0/1，库里冒出来一个 2——注释过期或有脏数据，这是最要紧的一种
        ColumnProfile p = profile("is_scan", List.of("0", "1", "2"), 3, true, 900, 0.1);
        ValueDomainVerdict v = ValueDomainCrosscheck.cross(p, "是否扫码 0=免扫码,1=必须扫码",
                List.of(), List.of());

        assertEquals(List.of("2"), v.onlyInDatabase());
        assertTrue(v.hasFinding());
        assertFalse(v.nothingDeclared(), "注释里声明过 0/1，这是「多出来一个值」不是「没声明」");
        assertTrue(v.summary().contains("库里存在未声明的值"), v.summary());
        // 建议值域以库为准：认得出语义的带语义，认不出的只留取值
        assertEquals(List.of("0=免扫码", "1=必须扫码", "2"), v.proposedEnumValues());
    }

    @Test
    void declaredValueNeverSeenInDatabase() {
        ColumnProfile p = profile("state", List.of("0", "1"), 2, true, 1000, 0.0);
        ValueDomainVerdict v = ValueDomainCrosscheck.cross(p,
                "任务状态  0未开始 1一开始 2已完成", List.of(), List.of());

        assertEquals(List.of("2"), v.onlyInDeclared());
        // 库里没出现的值不进建议值域——写进去 Agent 会以为那是个可用的筛选条件
        assertEquals(List.of("0=未开始", "1=一开始"), v.proposedEnumValues());
    }

    /** 只看 DDL 和注释永远得不出这个结论，而它能挡住 Agent 拿一个死字段做判断。 */
    @Test
    void constantColumnIsReportedAsUnused() {
        ColumnProfile p = profile("del_flag", List.of("0"), 1, true, 1000, 0.0);
        ValueDomainVerdict v = ValueDomainCrosscheck.cross(p, "删除标记 0=正常,1=已删除",
                List.of(), List.of());

        assertTrue(v.dead());
        assertTrue(v.summary().contains("实际未启用"), v.summary());
    }

    @Test
    void allNullColumnIsReportedAsUnused() {
        ColumnProfile p = profile("remark", List.of(), 0, true, 0, 1.0);
        assertTrue(ValueDomainCrosscheck.cross(p, null, List.of(), List.of()).dead());
    }

    // ---- 第三源与去噪 ----

    @Test
    void codeEnumOverridesCommentLabelForTheSameValue() {
        // 代码通常比注释新，同一个取值上代码的标签赢
        ColumnProfile p = profile("status", List.of("0", "1"), 2, true, 1000, 0.0);
        ValueDomainVerdict v = ValueDomainCrosscheck.cross(p, "状态 0=旧说法,1=另一个旧说法",
                List.of("0=待付款", "1=已付款"), List.of());

        assertEquals(List.of("0=待付款", "1=已付款"), v.proposedEnumValues());
    }

    /** 三源一致、图谱里也已经是这份值域时不该打扰人——否则每跑一次就刷一批审批。 */
    @Test
    void noFindingWhenEverythingAlreadyAgrees() {
        ColumnProfile p = profile("is_scan", List.of("0", "1"), 2, true, 1000, 0.0);
        ValueDomainVerdict v = ValueDomainCrosscheck.cross(p, "是否扫码 0=免扫码,1=必须扫码",
                List.of(), List.of("0=免扫码", "1=必须扫码"));

        assertTrue(v.unchanged());
        assertFalse(v.hasFinding());
    }

    /** 标签改个措辞不算值域变化——只比取值集合。 */
    @Test
    void relabelingAloneIsNotAChange() {
        ColumnProfile p = profile("is_scan", List.of("0", "1"), 2, true, 1000, 0.0);
        ValueDomainVerdict v = ValueDomainCrosscheck.cross(p, "是否扫码 0=不用扫,1=要扫",
                List.of(), List.of("0=免扫码", "1=必须扫码"));

        assertTrue(v.unchanged());
        assertFalse(v.hasFinding());
    }

    /**
     * 「是不是枚举」必须用**相对**基数判。
     *
     * <p>第一版用了 profiler 的 {@code lowCardinality}（distinct &lt; 50 的绝对阈值），
     * 在真库一张 39 行的表上 35 个字段报出 31 个「发现」——UUID 主键、计划名称、创建时间
     * 全被当成了枚举，因为 39 个不同值确实小于 50。
     */
    @Test
    void highCardinalityColumnIsSkippedNotGuessed() {
        ColumnProfile p = profile("order_no", List.of(), 9000, false, 9000, 0.0);
        ValueDomainVerdict v = ValueDomainCrosscheck.cross(p, "订单号", List.of(), List.of());

        assertTrue(v.skipped());
        assertFalse(v.hasFinding());
        assertTrue(v.skipReason().contains("几乎不重复"), v.skipReason());
    }

    /** 39 行的表上 39 个不同的 UUID——绝对基数看着低，相对基数是 1.0，是主键不是枚举。 */
    @Test
    void uniqueValuesInASmallTableAreNotAnEnum() {
        ColumnProfile p = new ColumnProfile("id", false, null, 39, 39, 0.0,
                39, true, List.of("027b541c", "080ba61e", "09514f43"), null, null, null, null, 0);
        ValueDomainVerdict v = ValueDomainCrosscheck.cross(p, "主键", List.of(), List.of());

        assertTrue(v.skipped(), "39/39 不重复，不该被当成枚举");
        assertFalse(v.hasFinding());
    }

    /** UUID / 时间戳这类长取值不是枚举，哪怕它恰好重复得够多。 */
    @Test
    void longValuesAreNotAnEnum() {
        ColumnProfile p = new ColumnProfile("cyc_type", false, null, 1000, 1000, 0.0,
                3, true, List.of("c6cfa5213e8d4b62b380f1decec4e2f4"), null, null, null, null, 0);
        ValueDomainVerdict v = ValueDomainCrosscheck.cross(p, null, List.of(), List.of());

        assertTrue(v.skipped());
        assertTrue(v.skipReason().contains("取值过长"), v.skipReason());
    }

    /** 采样行数太少时什么结论都不下——哪怕看起来像个枚举。 */
    @Test
    void tinySampleYieldsNoConclusion() {
        ColumnProfile p = new ColumnProfile("status", false, null, 8, 8, 0.0,
                2, true, List.of("0", "1"), null, null, null, null, 0);
        assertTrue(ValueDomainCrosscheck.cross(p, "状态 0=停用,1=启用", List.of(), List.of()).skipped());
    }

    /** 空串不是一个取值——写进值域会让 Agent 以为 col = '' 是个有意义的条件。 */
    @Test
    void blankValuesAreDropped() {
        ColumnProfile p = new ColumnProfile("week_periods", false, null, 1000, 1000, 0.0,
                2, true, java.util.Arrays.asList("", "1"), null, null, null, null, 0);
        ValueDomainVerdict v = ValueDomainCrosscheck.cross(p, null, List.of(), List.of());

        assertEquals(List.of("1"), v.proposedEnumValues());
    }

    /**
     * 「注释声明了 0/1、库里冒出个 2」和「注释压根没声明过」严重性差很远，说法必须分开。
     *
     * <p>真库上撞见过：`is_scan` 的注释只写了「是否扫码」，结果报成「库里存在未声明的值 [0,1]」，
     * 读起来像出了异常，其实只是这一列还没有值域。
     */
    @Test
    void missingDeclarationReadsDifferentlyFromAStaleOne() {
        ColumnProfile p = profile("is_scan", List.of("0", "1"), 2, true, 1000, 0.0);
        ValueDomainVerdict v = ValueDomainCrosscheck.cross(p, "是否扫码", List.of(), List.of());

        assertTrue(v.nothingDeclared());
        assertTrue(v.summary().contains("注释未声明值域"), v.summary());
        assertFalse(v.summary().contains("库里存在未声明的值"), v.summary());
    }

    /** 有发现但没有值域可写（整列全 NULL）——提不出审批，不能静默丢掉。 */
    @Test
    void unusedColumnIsReportOnly() {
        ColumnProfile p = profile("remark", List.of(), 0, true, 0, 1.0);
        ValueDomainVerdict v = ValueDomainCrosscheck.cross(p, null, List.of(), List.of());

        assertTrue(v.hasFinding());
        assertTrue(v.reportOnly(), "全 NULL 的列没有值域可提，只能报告");
    }

    /**
     * 取值一多就不是内联枚举——`ColumnValueHints` 的注释早就写了：
     * 「值多到不该内联的（字典表）不在这里」。
     *
     * <p>真库漏网样本：`sort` 是 1~17 的排序号、`version` 是乐观锁版本号，
     * 两者相对基数都够低，只有取值个数能拦住。
     */
    @Test
    void tooManyValuesIsADictionaryOrACounterNotAnEnum() {
        ColumnProfile p = new ColumnProfile("sort", false, null, 1000, 1000, 0.0,
                17, true, List.of("1", "2", "3"), null, null, null, null, 0);
        ValueDomainVerdict v = ValueDomainCrosscheck.cross(p, "顺序", List.of(), List.of());

        assertTrue(v.skipped());
        assertTrue(v.skipReason().contains("取值过多"), v.skipReason());
    }

    /**
     * 图谱里已有的值域是**第四个语义源**，而且是人批准过的那个——绝不能被抹掉。
     *
     * <p>真库上差点酿成事故：`is_scan` 的注释只有「是否扫码」不含值域声明，
     * 而图谱里已经写好了 `0=免扫码,1=必须扫码`。第一版只把注释和代码当语义源，
     * 于是提议是把标签冲成裸的 `['1','0']`——批准一下就毁掉人工整理的成果，
     * 而且还会被误报成一条「发现」。
     */
    @Test
    void existingGraphLabelsSurviveWhenTheCommentSaysNothing() {
        ColumnProfile p = profile("is_scan", List.of("1", "0"), 2, true, 1000, 0.0);
        List<String> declared = List.of("0=免扫码", "1=必须扫码");
        ValueDomainVerdict v = ValueDomainCrosscheck.cross(p, "是否扫码", List.of(), declared);

        assertFalse(v.hasFinding(), "图谱里已经声明过了，不该报成发现：" + v.summary());
        assertTrue(v.onlyInDatabase().isEmpty(), "图谱已有的值域算已声明");
        // 标签必须保留下来，不能退化成裸取值
        assertTrue(v.proposedEnumValues().contains("0=免扫码"), v.proposedEnumValues().toString());
        assertTrue(v.proposedEnumValues().contains("1=必须扫码"), v.proposedEnumValues().toString());
    }

    /** 代码枚举比图谱已有的新时，代码赢——但只在同一个取值上，不整份替换。 */
    @Test
    void codeEnumWinsOverTheGraphOnTheSameValue() {
        ColumnProfile p = profile("status", List.of("0", "1"), 2, true, 1000, 0.0);
        ValueDomainVerdict v = ValueDomainCrosscheck.cross(p, null,
                List.of("1=已支付"), List.of("0=待付款", "1=旧说法"));

        assertEquals(List.of("0=待付款", "1=已支付"), v.proposedEnumValues());
    }

    /**
     * 点名某一列时，那组「这是不是枚举」的启发式不该再否决它。
     *
     * <p>它们是给**整表扫描**防噪音用的。调用方点名并给出代码枚举，等于已经回答了
     * 「它是不是枚举」——继续拦就是跟调用方对着干。真库上撞见过：agent 点名 `task_type`
     * 给了 6 个取值的枚举，却因为 6/39 超过相对基数阈值被判「不是枚举」，
     * 还报成「三源一致」。
     */
    @Test
    void namingAColumnExplicitlyOverridesTheEnumHeuristics() {
        // 6/39 超过 0.1 的相对基数阈值，整表扫描时会被跳过
        ColumnProfile p = new ColumnProfile("task_type", false, null, 39, 39, 0.0,
                6, true, List.of("0", "1", "2", "3", "5", "6"), null, null, null, null, 0);

        assertTrue(ValueDomainCrosscheck.cross(p, null, List.of(), List.of()).skipped(),
                "整表扫描时该跳过");

        ValueDomainVerdict asserted = ValueDomainCrosscheck.cross(p, null,
                List.of("0=日常巡检", "1=专项巡检"), List.of(), true);
        assertFalse(asserted.skipped(), "点名了就该给结论");
        // 代码只覆盖了 2 个，其余 4 个库里有、没有语义——正是要报的发现
        assertEquals(List.of("2", "3", "5", "6"), asserted.onlyInDatabase());
    }
}
