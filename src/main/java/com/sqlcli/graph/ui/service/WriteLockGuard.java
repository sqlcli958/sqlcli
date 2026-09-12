package com.sqlcli.graph.ui.service;

import java.util.concurrent.locks.ReentrantReadWriteLock;

public class WriteLockGuard implements AutoCloseable {
    private final ReentrantReadWriteLock rwLock;
    private final FileLockHolder fileLockHolder;

    public WriteLockGuard(ReentrantReadWriteLock rwLock, FileLockHolder fileLockHolder) {
        this.rwLock = rwLock;
        this.fileLockHolder = fileLockHolder;
    }

    @Override
    public void close() {
        if (fileLockHolder != null) {
            fileLockHolder.release();
        }
        if (rwLock != null) {
            rwLock.writeLock().unlock();
        }
    }
}
