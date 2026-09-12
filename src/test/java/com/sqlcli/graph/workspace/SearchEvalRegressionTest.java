package com.sqlcli.graph.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.sqlcli.graph.workspace.index.WorkspaceIndexSnapshot;
import com.sqlcli.graph.workspace.index.WorkspaceIndexedSearchEngine;
import com.sqlcli.graph.workspace.index.WorkspaceIndexer;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 排序回归：真实评估集（src/test/resources/search-eval-cases.shop.yaml）跑一个手搭的电商
 * fixture 工作区，锁住 Top1/Top5/MRR 的下限，并钉住「实时引擎与索引引擎排序应一致」——
 * 这正是 SearchMatching 里共享权重常量要保证的事：索引失效回退到实时引擎时，同一个查询
 * 不该换一套排序结果。
 *
 * <p>阈值是在当前实现上先跑一遍拿到实际值，再降 5% 作为下限：以后调 BM25 / 权重参数，
 * 这几个数掉下去说明排序变差了，不是"随便碰了下就报警"的敏感线。
 */
class SearchEvalRegressionTest {

    private static final String ALIAS = "shop-eval";
    private static final String SCHEMA = "app";

    @Test
    void realtimeEngineMeetsRankingFloor() throws Exception {
        List<SearchEval.EvalCase> cases = loadCases();
        WorkspaceSearchEngine engine = new WorkspaceSearchEngine(buildWorkspace());
        Function<String, List<String>> search = query -> engine.search(query).stream()
                .map(WorkspaceSearchEngine.SearchResult::getId).toList();

        SearchEval.Report report = SearchEval.evaluate(cases, search);
        // 实测 top1=top5=mrr=0.90625（29/32 命中且全是第一名），阈值降到 0.85 留够 5%+ 余量。
        // 这是排序回归的下限：调 BM25 / 权重参数后如果掉到这条线以下，说明排序变差了。
        assertTrue(report.top1Rate() >= 0.85, "实时引擎 Top1 掉到下限以下: " + report.top1Rate());
        assertTrue(report.top5Rate() >= 0.85, "实时引擎 Top5 掉到下限以下: " + report.top5Rate());
        assertTrue(report.mrr() >= 0.85, "实时引擎 MRR 掉到下限以下: " + report.mrr());
    }

    @Test
    void indexedEngineMeetsRankingFloor() throws Exception {
        List<SearchEval.EvalCase> cases = loadCases();
        WorkspaceIndexSnapshot snapshot = new WorkspaceIndexer().rebuild(buildWorkspace());
        WorkspaceIndexedSearchEngine engine = new WorkspaceIndexedSearchEngine(snapshot);
        Function<String, List<String>> search = query -> engine.search(query).stream()
                .map(WorkspaceIndexedSearchEngine.SearchHit::getId).toList();

        SearchEval.Report report = SearchEval.evaluate(cases, search);
        // 实测 top1=top5=mrr=0.90625（29/32 命中且全是第一名），阈值降到 0.85 留够 5%+ 余量。
        assertTrue(report.top1Rate() >= 0.85, "索引引擎 Top1 掉到下限以下: " + report.top1Rate());
        assertTrue(report.top5Rate() >= 0.85, "索引引擎 Top5 掉到下限以下: " + report.top5Rate());
        assertTrue(report.mrr() >= 0.85, "索引引擎 MRR 掉到下限以下: " + report.mrr());
    }

    /**
     * 索引失效时 WorkspaceQueryController 会自动回退到实时引擎；两边权重共用
     * {@link SearchMatching} 常量，同一份评估集在两个引擎上的 Top1 命中集合必须一致，
     * 否则用户会看到"索引过期"前后排序不一样。
     */
    @Test
    void realtimeAndIndexedEnginesAgreeOnTop1() throws Exception {
        List<SearchEval.EvalCase> cases = loadCases();
        GraphWorkspace workspace = buildWorkspace();

        WorkspaceSearchEngine realtime = new WorkspaceSearchEngine(workspace);
        WorkspaceIndexedSearchEngine indexed =
                new WorkspaceIndexedSearchEngine(new WorkspaceIndexer().rebuild(workspace));

        for (SearchEval.EvalCase evalCase : cases) {
            Set<String> realtimeTop1 = top1Ids(realtime.search(evalCase.query()).stream()
                    .map(WorkspaceSearchEngine.SearchResult::getId).toList());
            Set<String> indexedTop1 = top1Ids(indexed.search(evalCase.query()).stream()
                    .map(WorkspaceIndexedSearchEngine.SearchHit::getId).toList());
            assertEquals(realtimeTop1, indexedTop1,
                    "查询 \"" + evalCase.query() + "\" 在两个引擎上的 Top1 不一致");
        }
    }

    private static Set<String> top1Ids(List<String> ids) {
        // id 前缀带类型（table:/column:），只比对同名对象是否一致即可，忽略大小写
        return ids.isEmpty() ? Set.of() : new LinkedHashSet<>(List.of(ids.get(0).toLowerCase()));
    }

    private static List<SearchEval.EvalCase> loadCases() throws Exception {
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
        try (InputStream in = SearchEvalRegressionTest.class.getClassLoader()
                .getResourceAsStream("search-eval-cases.shop.yaml")) {
            JsonNode root = yaml.readTree(in);
            List<SearchEval.EvalCase> cases = new ArrayList<>();
            for (JsonNode caseNode : root.path("cases")) {
                String query = caseNode.path("query").asText(null);
                List<String> expect = new ArrayList<>();
                for (JsonNode idNode : caseNode.path("expect")) {
                    expect.add(idNode.asText());
                }
                cases.add(new SearchEval.EvalCase(query, expect));
            }
            return cases;
        }
    }

    /** 电商 fixture：订单/用户/商品/支付/物流五张表，字段带中文业务名与注释。 */
    private static GraphWorkspace buildWorkspace() {
        GraphWorkspace workspace = GraphWorkspace.create(ALIAS, "mysql");

        TableWorkspaceNode orders = table("orders", "订单", "用户下单生成的订单主表");
        column(orders, "id", SemanticType.primary_id, "订单编号", "订单主键ID");
        column(orders, "buyer_phone", SemanticType.phone, "买家手机号码", "买家联系电话，下单人手机号");
        column(orders, "status", SemanticType.status, "订单状态", "订单当前所处的状态：待支付/已发货/已完成");
        column(orders, "amount", SemanticType.amount, "订单金额", "订单应付金额，单位分");
        column(orders, "created_at", SemanticType.created_at, "下单时间", "订单创建时间");
        workspace.getTables().put(orders.getId(), orders);

        TableWorkspaceNode users = table("users", "用户", "平台注册用户信息表");
        column(users, "id", SemanticType.primary_id, null, null);
        column(users, "phone", SemanticType.phone, "登录手机号", "用户注册使用的手机号码");
        column(users, "nickname", SemanticType.name, "昵称", "用户展示昵称");
        workspace.getTables().put(users.getId(), users);

        TableWorkspaceNode products = table("products", "商品", "商品基础信息表，含库存与价格");
        column(products, "id", SemanticType.primary_id, null, null);
        column(products, "title", SemanticType.name, "商品标题", "商品展示名称");
        column(products, "stock", SemanticType.quantity, "库存数量", "商品剩余库存件数");
        column(products, "price", SemanticType.amount, "商品价格", "商品销售单价，单位分");
        workspace.getTables().put(products.getId(), products);

        TableWorkspaceNode payments = table("payments", "支付流水", "订单支付记录，记录支付渠道与结果");
        column(payments, "id", SemanticType.primary_id, null, null);
        column(payments, "order_id", SemanticType.ref_id, "关联订单", "关联的订单主键");
        column(payments, "pay_channel", SemanticType.code, "支付渠道", "微信/支付宝/银行卡等支付方式");
        column(payments, "pay_amount", SemanticType.amount, "实付金额", "用户实际支付的金额");
        workspace.getTables().put(payments.getId(), payments);

        TableWorkspaceNode logistics = table("logistics", "物流", "订单发货与物流轨迹信息");
        column(logistics, "id", SemanticType.primary_id, null, null);
        column(logistics, "order_id", SemanticType.ref_id, "关联订单", null);
        column(logistics, "tracking_no", SemanticType.code, "运单号", "快递公司分配的运单编号");
        column(logistics, "courier", SemanticType.name, "快递公司", "承运的快递公司名称");
        workspace.getTables().put(logistics.getId(), logistics);

        return workspace;
    }

    private static TableWorkspaceNode table(String name, String businessName, String comment) {
        TableWorkspaceNode node = TableWorkspaceNode.create(ALIAS, SCHEMA, name, GraphActor.extractor);
        node.setBusinessName(businessName);
        node.setComment(comment);
        return node;
    }

    private static void column(TableWorkspaceNode table, String name, SemanticType semanticType,
            String businessName, String comment) {
        ColumnWorkspaceNode column = ColumnWorkspaceNode.create(name);
        column.setSemanticType(semanticType);
        column.setBusinessName(businessName);
        column.setComment(comment);
        table.getColumns().add(column);
    }
}
