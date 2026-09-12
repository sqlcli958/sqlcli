package com.sqlcli.graph.workspace;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * 血缘遍历：按 target / source 各建一个 map 索引（查一跳 O(1)），
 * BFS 展开到指定深度，环安全（按记录 id 去重）。
 */
public final class LineageGraph {
    private final Map<String, List<LineageRecord>> byTarget = new LinkedHashMap<>();
    private final Map<String, List<LineageRecord>> bySource = new LinkedHashMap<>();

    public LineageGraph(Collection<LineageRecord> records) {
        for (LineageRecord record : records) {
            if (record.getTarget() != null) {
                byTarget.computeIfAbsent(key(record.getTarget()), ignored -> new ArrayList<>()).add(record);
            }
            for (String source : record.getSources()) {
                bySource.computeIfAbsent(key(source), ignored -> new ArrayList<>()).add(record);
            }
        }
    }

    /** 顺着 target 往源头走：这些列由谁推导而来。 */
    public List<LineageRecord> upstream(String columnId, int depth) {
        return traverse(columnId, depth, true);
    }

    /** 顺着 source 往下游走：这个列参与推导了谁。 */
    public List<LineageRecord> downstream(String columnId, int depth) {
        return traverse(columnId, depth, false);
    }

    private List<LineageRecord> traverse(String columnId, int depth, boolean up) {
        List<LineageRecord> results = new ArrayList<>();
        Set<String> seenRecords = new LinkedHashSet<>();
        Set<String> seenColumns = new LinkedHashSet<>();
        Queue<String> frontier = new ArrayDeque<>();
        String start = key(columnId);
        frontier.add(start);
        seenColumns.add(start);
        for (int level = 0; level < Math.max(1, depth) && !frontier.isEmpty(); level++) {
            int size = frontier.size();
            for (int i = 0; i < size; i++) {
                String column = frontier.poll();
                for (LineageRecord record : (up ? byTarget : bySource).getOrDefault(column, List.of())) {
                    if (!seenRecords.add(record.getId())) {
                        continue;
                    }
                    results.add(record);
                    List<String> next = up ? record.getSources()
                            : (record.getTarget() == null ? List.of() : List.of(record.getTarget()));
                    for (String nextColumn : next) {
                        String nextKey = key(nextColumn);
                        if (seenColumns.add(nextKey)) {
                            frontier.add(nextKey);
                        }
                    }
                }
            }
        }
        return results;
    }

    private static String key(String columnId) {
        return columnId == null ? "" : columnId.toLowerCase(Locale.ROOT);
    }
}
