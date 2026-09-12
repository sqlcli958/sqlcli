package com.sqlcli.graph.workspace;

public enum ChangeOperation {
    create,
    update,
    delete,
    upsert,
    upsert_table,
    upsert_column,
    upsert_relation,
    verify,
    deprecate,
    ignore
}
