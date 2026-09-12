package com.sqlcli.graph.ui.service;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;

public class FileLockHolder {
    private final FileLock fileLock;
    private final RandomAccessFile raf;

    public FileLockHolder(FileLock fileLock, RandomAccessFile raf) {
        this.fileLock = fileLock;
        this.raf = raf;
    }

    public void release() {
        try {
            if (fileLock != null && fileLock.isValid()) {
                fileLock.release();
            }
        } catch (IOException ignored) {}
        try {
            if (raf != null) raf.close();
        } catch (IOException ignored) {}
    }
}
