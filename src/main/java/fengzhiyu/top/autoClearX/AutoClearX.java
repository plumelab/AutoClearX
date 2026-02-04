package fengzhiyu.top.autoClearX;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public class AutoClearX extends JavaPlugin {
    private BenchmarkManager benchmarkManager;
    private SweepManager sweepManager;
    private LangManager langManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        langManager = new LangManager(this);
        benchmarkManager = new BenchmarkManager(this, langManager);
        benchmarkManager.initialize();
        sweepManager = new SweepManager(this, benchmarkManager, langManager);
        sweepManager.start();

        PluginCommand command = getCommand("sweepbench");
        if (command != null) {
            SweepBenchCommand executor = new SweepBenchCommand(benchmarkManager, langManager);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        } else {
            getLogger().warning(langManager.get("log.command-not-registered", "command", "sweepbench"));
        }

        PluginCommand sweepCommand = getCommand("sweep");
        if (sweepCommand != null) {
            SweepCommand executor = new SweepCommand(sweepManager, langManager);
            sweepCommand.setExecutor(executor);
            sweepCommand.setTabCompleter(executor);
        } else {
            getLogger().warning(langManager.get("log.command-not-registered", "command", "sweep"));
        }

        long delayTicks = BenchmarkManager.START_DELAY_MINUTES * 60L * 20L;
        benchmarkManager.scheduleAutoBenchmark(delayTicks);
    }

    @Override
    public void onDisable() {
        if (sweepManager != null) {
            sweepManager.shutdown();
        }
        if (benchmarkManager != null) {
            benchmarkManager.shutdown();
        }
    }
}
