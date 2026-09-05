package com.mkr;

import com.mkr.cli.PicocliCommands;
import picocli.CommandLine;

/**
 * mkr 入口：无参数默认进入 REPL；有子命令按 picocli 分派
 * （init / run / eval / tools / model / cost / reset / skills / mcp / permissions）。
 */
public final class Main {

    public static void main(String[] args) {
        // cwd() 读 user.dir，置为绝对路径后 init/run 的所有相对路径都落在 /tmp/mkr-smoke
//        System.setProperty("user.dir", "/tmp/mkr-smoke");
//        args = new String[]{"init"};

//        args = new String[]{"run", "--permission-mode", "auto-approve", "给定条件（如“5天川西自驾”或“3天江浙沪周边游”），要求 Agent 必须查阅两地真实距离、路况时间以及景点的近期开放状态，输出一份无逻辑冲突的日程规划。"};

//        args = new String[]{"run", "--mode", "plan", "--permission-mode", "auto-approve", "检索北京、上海、广州当前最新的二手房均价（或最新人口/GDP数据），并计算它们相比上一年的增跌幅，最后按跌幅输出排名表"};

        args = new String[]{"run", "--permission-mode", "auto-approve", "调研含有“特丁基对苯二酚 (TBHQ)”的零食，或含“吡虫啉”的家用杀虫剂。要求查阅最新的国家食品安全标准或专业文献，明确给出其对人体或宠物（如猫）的潜在风险。"};

//        args = new String[]{"run", "--mode", "manager", "--permission-mode", "auto-approve", "冒烟测试，直接委派，不要做长篇规划：派 2 个子 Agent——A 把一行文本 'A ok' 写入 workspace/smoke_a.md，B 把一行文本 'B ok' 写入 workspace/smoke_b.md。两个摘要都返回后，你读回这两个文件，最终答复里逐字给出两个文件的内容。"};

        if (args.length == 0 || (args.length == 1 && "--repl".equals(args[0]))) {
            args = new String[]{"repl"};
        }
        int code = new CommandLine(new PicocliCommands()).execute(args);
        System.exit(code);
    }

    private Main() {
    }
}
