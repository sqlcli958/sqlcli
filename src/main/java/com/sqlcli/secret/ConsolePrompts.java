package com.sqlcli.secret;

import java.io.Console;
import java.util.Scanner;

/**
 * 控制台输入辅助
 */
public final class ConsolePrompts {
    private ConsolePrompts() {
    }

    public static char[] readPassword(String prompt) {
        Console console = System.console();
        if (console != null) {
            char[] value = console.readPassword(prompt);
            if (value != null) {
                return value;
            }
        }
        System.out.print(prompt);
        Scanner scanner = new Scanner(System.in);
        return scanner.nextLine().toCharArray();
    }

    public static String readLine(String prompt) {
        Console console = System.console();
        if (console != null) {
            String value = console.readLine(prompt);
            return value == null ? "" : value;
        }
        System.out.print(prompt);
        Scanner scanner = new Scanner(System.in);
        return scanner.nextLine();
    }
}
