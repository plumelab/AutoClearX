package fengzhiyu.top.autoClearX;

import java.util.Arrays;
import java.util.List;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

public class SweepCommand implements CommandExecutor, TabCompleter {
    private final SweepManager sweepManager;
    private final LangManager langManager;

    public SweepCommand(SweepManager sweepManager, LangManager langManager) {
        this.sweepManager = sweepManager;
        this.langManager = langManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(langManager.get("command.sweep.usage"));
            return true;
        }
        String sub = args[0].toLowerCase();
        switch (sub) {
            case "status" -> sender.sendMessage(buildStatusMessage());
            case "now" -> {
                if (!sender.isOp()) {
                    sender.sendMessage(langManager.get("command.sweep.no-permission-now"));
                    return true;
                }
                boolean triggered = sweepManager.triggerImmediateSweep();
                if (triggered) {
                    sender.sendMessage(langManager.get("command.sweep.triggered"));
                } else {
                    sender.sendMessage(langManager.get("command.sweep.no-target"));
                }
            }
            case "trash" -> {
                if (!(sender instanceof org.bukkit.entity.Player player)) {
                    sender.sendMessage(langManager.get("command.sweep.player-only-trash"));
                    return true;
                }
                player.openInventory(sweepManager.getTrashInventory());
            }
            default -> sender.sendMessage(langManager.get("command.sweep.unknown-subcommand"));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("status", "now", "trash");
        }
        return List.of();
    }

    private String buildStatusMessage() {
        SweepManager.SweepStatus status = sweepManager.getStatus();
        String target = status.targetText();
        String top = status.topText();
        String globalLine;
        String topLine;
        if (!status.thresholds().isValid()) {
            globalLine = langManager.get(
                "sweep.status.global-unavailable",
                "current",
                String.valueOf(status.globalCount())
            );
            topLine = langManager.get(
                "sweep.status.top-unavailable",
                "top",
                top
            );
        } else {
            globalLine = langManager.get(
                "sweep.status.global",
                "current",
                String.valueOf(status.globalCount()),
                "threshold",
                String.format("%.2f", status.thresholds().globalTrigger())
            );
            topLine = langManager.get(
                "sweep.status.top",
                "top",
                top,
                "threshold",
                String.format("%.2f", status.thresholds().chunkTrigger())
            );
        }
        return langManager.get("sweep.status.header") + "\n"
            + langManager.get(
                "sweep.status.state",
                "state",
                langManager.get("sweep.state." + status.state().name().toLowerCase())
            ) + "\n"
            + langManager.get("sweep.status.target", "target", target) + "\n"
            + langManager.get("sweep.status.countdown", "countdown", String.valueOf(status.countdown())) + "\n"
            + globalLine + "\n"
            + topLine;
    }
}
