package com.sqlcli.graph.ui.service;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 工作区锁管理器：alias 级进程内 ReadWriteLock + 跨进程文件锁。
 * 查询可并发，修改串行执行。
 */
public class WorkspaceLockManager {

    private static final ConcurrentHashMap<String, ReentrantReadWriteLock> IN_PROCESS_LOCKS = new ConcurrentHashMap<>();

    /**
     * 获取读锁（允许并发查询）
     */
    public ReentrantReadWriteLock.ReadLock acquireReadLock(String alias, long timeoutMs) throws WorkspaceLockException {
        ReentrantReadWriteLock rwLock = IN_PROCESS_LOCKS.computeIfAbsent(alias, k -> new ReentrantReadWriteLock(true));
        try {
            if (!rwLock.readLock().tryLock(timeoutMs, TimeUnit.MILLISECONDS)) {
                throw new WorkspaceLockException("获取读锁超时: " + alias);
            }
            return rwLock.readLock();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WorkspaceLockException("获取读锁被中断: " + alias);
        }
    }

    /**
     * 获取写锁（串行修改）+ 跨进程文件锁
     */
    public WriteLockGuard acquireWriteLock(String alias, Path workspaceRoot, long timeoutMs) throws WorkspaceLockException {
        // 1. 进程内写锁
        ReentrantReadWriteLock rwLock = IN_PROCESS_LOCKS.computeIfAbsent(alias, k -> new ReentrantReadWriteLock(true));
        try {
            if (!rwLock.writeLock().tryLock(timeoutMs, TimeUnit.MILLISECONDS)) {
                throw new WorkspaceLockException("获取写锁超时: " + alias);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WorkspaceLockException("获取写锁被中断: " + alias);
        }

        // 2. 跨进程文件锁
        FileLockHolder fileLockHolder = null;
        try {
            fileLockHolder = acquireFileLock(alias, workspaceRoot, timeoutMs);
        } catch (WorkspaceLockException e) {
            rwLock.writeLock().unlock(); // 回滚进程内锁
            throw e;
        }

        return new WriteLockGuard(rwLock, fileLockHolder);
    }

    private FileLockHolder acquireFileLock(String alias, Path workspaceRoot, long timeoutMs) throws WorkspaceLockException {
        RandomAccessFile raf = null;
        try {
            Files.createDirectories(workspaceRoot);
            Path lockFile = workspaceRoot.resolve(".workspace.lock");
            raf = new RandomAccessFile(lockFile.toFile(), "rw");
            FileChannel channel = raf.getChannel();
            FileLock fileLock = channel.tryLock();
            if (fileLock == null) {
                raf.close();
                throw new WorkspaceLockException("工作区正被另一个进程使用: " + alias, "workspace_busy");
            }
            return new FileLockHolder(fileLock, raf);
        } catch (IOException | OverlappingFileLockException e) {
            if (raf != null) {
                try {
                    raf.close();
                } catch (IOException ignored) {
                    // Preserve the lock acquisition error.
                }
            }
            throw new WorkspaceLockException("获取文件锁失败: " + alias + " - " + e.getMessage(), "workspace_busy");
        }
    }

    /**
     * 检查工作区是否被锁定
     */
    public boolean isLocked(String alias) {
        ReentrantReadWriteLock rwLock = IN_PROCESS_LOCKS.get(alias);
        return rwLock != null && (rwLock.isWriteLocked() || rwLock.getReadLockCount() > 0);
    }
}
