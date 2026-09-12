package com.sqlcli.yearning;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Getter
@Setter
public class YearningQueryResult {
    private final List<String> columns = new ArrayList<>();
    private final List<Map<String, Object>> rows = new ArrayList<>();
    private int total;
    private long elapsedMs;

    public void addRow(Map<String, Object> row) {
        rows.add(new LinkedHashMap<>(row));
    }
}
