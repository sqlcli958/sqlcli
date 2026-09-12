package com.sqlcli.testsupport;

import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 符号链接相关的测试辅助。
 *
 * <p>Windows 上创建符号链接需要 SeCreateSymbolicLinkPrivilege（管理员或开启开发者模式），
 * 普通账户会得到 {@link FileSystemException}。以符号链接为前置条件的测试在缺少该权限时
 * 应当跳过而不是失败——被验证的产品行为本身与平台无关。
 */
public final class Symlinks {

    private Symlinks() {
    }

    /**
     * 创建符号链接；若当前环境不允许创建，则中止（跳过）调用方测试。
     */
    public static void createOrAbort(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (FileSystemException e) {
            Assumptions.abort("当前环境不允许创建符号链接（Windows 需要管理员或开发者模式）: " + e.getMessage());
        } catch (UnsupportedOperationException e) {
            Assumptions.abort("当前文件系统不支持符号链接: " + e.getMessage());
        }
    }
}
