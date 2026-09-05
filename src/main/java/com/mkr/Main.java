package com.mkr;

import com.mkr.cli.PicocliCommands;
import picocli.CommandLine;

/**
 * mkr 入口：无参数默认进入 REPL；有子命令按 picocli 分派
 * （init / run / eval / tools / model / cost / reset / skills / mcp / permissions）。
 */
public final class Main {

    public static void main(String[] args) {
        if (args.length == 0 || (args.length == 1 && "--repl".equals(args[0]))) {
            args = new String[]{"repl"};
        }
        int code = new CommandLine(new PicocliCommands()).execute(args);
        System.exit(code);
    }

    private Main() {
    }
}
