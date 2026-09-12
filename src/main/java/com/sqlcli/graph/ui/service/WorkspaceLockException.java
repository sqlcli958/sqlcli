package com.sqlcli.graph.ui.service;

public class WorkspaceLockException extends RuntimeException {
    private final String errorCode;

    public WorkspaceLockException(String message) {
        this(message, "lock_timeout");
    }

    public WorkspaceLockException(String message, String errorCode) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() { return errorCode; }
}
