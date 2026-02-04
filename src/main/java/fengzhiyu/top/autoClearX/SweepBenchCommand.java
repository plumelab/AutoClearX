package fengzhiyu.top.autoClearX;

import java.util.Arrays;
import java.util.List;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

public class SweepBenchCommand implements CommandExecutor, TabCompleter {
    private final BenchmarkManager benchmarkManager;
    private final LangManager langManager;

    public SweepBenchCommand(BenchmarkManager benchmarkManager, LangManager langManager) {
        this.benchmarkManager = benchmarkManager;
        this.langManager = langManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(langManager.get("command.sweepbench.usage"));
            return true;
        }
        String sub = args[0].toLowerCase();
        switch (sub) {
            case "run" -> {
                if (!sender.isOp()) {
                    sender.sendMessage(langManager.get("command.sweepbench.no-permission-run"));
                    return true;
                }
                boolean started = benchmarkManager.startManualBenchmark();
                if (started) {
                    sender.sendMessage(langManager.get("command.sweepbench.started"));
                } else {
                    sender.sendMessage(langManager.get("command.sweepbench.already-running"));
                }
            }
            case "status" -> sender.sendMessage(benchmarkManager.getStatusMessage());
            case "clear" -> {
                if (!sender.isOp()) {
                    sender.sendMessage(langManager.get("command.sweepbench.no-permission-clear"));
                    return true;
                }
                benchmarkManager.clearBenchmarkWorld();
                sender.sendMessage(langManager.get("command.sweepbench.cleared"));
            }
            default -> sender.sendMessage(langManager.get("command.sweepbench.unknown-subcommand"));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("run", "status", "clear");
        }
        return List.of();
    }
}
