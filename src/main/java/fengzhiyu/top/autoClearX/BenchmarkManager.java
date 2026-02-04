package fengzhiyu.top.autoClearX;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

public class BenchmarkManager implements Listener {
    public static final String BENCHMARK_WORLD_NAME = "sweep_benchmark";
    public static final int START_DELAY_MINUTES = 10;
    private static final int CHUNK_RADIUS = 2;
    private static final int BASELINE_SAMPLE_SECONDS = 20;
    private static final int STABILIZE_SECONDS = 10;
    private static final int SAMPLE_SECONDS = 20;
    private static final int[] INITIAL_STEPS = {25, 50, 100, 200};
    private static final int STEP_INCREMENT = 200;
    private static final int HARD_CAP_ENTITIES = 2000;

    private final JavaPlugin plugin;
    private final BenchmarkStorage storage;
    private final LangManager langManager;
    private final Random random = new Random();
    private State state = State.IDLE;
    private Round currentRound = Round.SINGLE;
    private int currentTarget = 0;
    private int stopHits = 0;
    private World benchmarkWorld;
    private BukkitTask samplingTask;
    private BukkitTask delayedStartTask;
    private BenchmarkRunData runData;
    private double effectiveThresholdSingle;
    private double effectiveThresholdArea;
    private BenchmarkRecord lastRecord;

    public BenchmarkManager(JavaPlugin plugin, LangManager langManager) {
        this.plugin = plugin;
        this.langManager = langManager;
        this.storage = new BenchmarkStorage(plugin.getDataFolder());
    }

    public void initialize() {
        benchmarkWorld = ensureBenchmarkWorld();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        reloadEffectiveThresholds();
    }

    public void shutdown() {
        abortRun();
    }

    public BenchmarkRecord getLastRecord() {
        return lastRecord;
    }

    public double getEffectiveThresholdSingle() {
        return effectiveThresholdSingle;
    }

    public double getEffectiveThresholdArea() {
        return effectiveThresholdArea;
    }

    public int getHardCapEntities() {
        return HARD_CAP_ENTITIES;
    }

    public void scheduleAutoBenchmark(long delayTicks) {
        if (state != State.IDLE) {
            return;
        }
        state = State.WAIT_DELAY;
        delayedStartTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            delayedStartTask = null;
            if (state == State.WAIT_DELAY) {
                startBenchmark();
            }
        }, delayTicks);
    }

    public boolean startManualBenchmark() {
        if (state == State.WAIT_DELAY) {
            if (delayedStartTask != null) {
                delayedStartTask.cancel();
                delayedStartTask = null;
            }
            startBenchmark();
            return true;
        }
        if (state != State.IDLE) {
            return false;
        }
        startBenchmark();
        return true;
    }

    public void clearBenchmarkWorld() {
        abortRun();
        if (benchmarkWorld != null) {
            clearEntities(benchmarkWorld);
        }
    }

    public String getStatusMessage() {
        StringBuilder builder = new StringBuilder();
        builder.append(langManager.get("sweepbench.status.header")).append("\n");
        builder.append(langManager.get(
            "sweepbench.status.state",
            "state",
            langManager.get("sweepbench.state." + state.name().toLowerCase())
        )).append("\n");
        builder.append(langManager.get(
            "sweepbench.status.round",
            "round",
            langManager.get("sweepbench.round." + currentRound.name().toLowerCase())
        )).append("\n");
        if (runData != null && runData.lastRecord != null) {
            builder.append(langManager.get(
                "sweepbench.status.last-slope",
                "single",
                String.format("%.6f", runData.lastRecord.slopeSingle),
                "multi",
                String.format("%.6f", runData.lastRecord.slopeMulti)
            )).append("\n");
        } else if (lastRecord != null) {
            builder.append(langManager.get(
                "sweepbench.status.last-slope",
                "single",
                String.format("%.6f", lastRecord.slopeSingle),
                "multi",
                String.format("%.6f", lastRecord.slopeMulti)
            )).append("\n");
        }
        builder.append(langManager.get(
            "sweepbench.status.thresholds",
            "single",
            String.format("%.2f", effectiveThresholdSingle),
            "area",
            String.format("%.2f", effectiveThresholdArea)
        ));
        return builder.toString();
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        if (benchmarkWorld == null || !event.getWorld().equals(benchmarkWorld)) {
            return;
        }
        Chunk chunk = event.getChunk();
        if (!isChunkInBounds(chunk.getX(), chunk.getZ())) {
            Bukkit.getScheduler().runTask(plugin, () -> event.getWorld().unloadChunk(chunk));
        }
    }

    @EventHandler
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (benchmarkWorld == null || event.getTo() == null) {
            return;
        }
        if (event.getTo().getWorld() != null && event.getTo().getWorld().equals(benchmarkWorld)) {
            event.setCancelled(true);
            teleportPlayerToMain(event.getPlayer());
        }
    }

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        if (benchmarkWorld == null) {
            return;
        }
        if (event.getPlayer().getWorld().equals(benchmarkWorld)) {
            teleportPlayerToMain(event.getPlayer());
        }
    }

    private void teleportPlayerToMain(Player player) {
        World mainWorld = Bukkit.getWorlds().getFirst();
        Location safe = mainWorld.getSpawnLocation();
        Bukkit.getScheduler().runTask(plugin, () -> player.teleport(safe));
    }

    private void startBenchmark() {
        runData = new BenchmarkRunData();
        state = State.CHECK_BASELINE;
        currentRound = Round.SINGLE;
        beginBaseline();
    }

    private void beginBaseline() {
        if (benchmarkWorld == null) {
            plugin.getLogger().warning(langManager.get("log.benchmark-world-unavailable"));
            state = State.IDLE;
            return;
        }
        clearEntities(benchmarkWorld);
        sampleMspt(BASELINE_SAMPLE_SECONDS, baseline -> {
            runData.setBaseline(currentRound, baseline);
            if (baseline >= 40.0) {
                plugin.getLogger().warning(langManager.get("log.benchmark-baseline-too-high"));
                state = State.IDLE;
                return;
            }
            currentTarget = 0;
            stopHits = 0;
            advanceRoundStep();
        });
    }

    private void advanceRoundStep() {
        if (state == State.IDLE) {
            return;
        }
        state = currentRound == Round.SINGLE ? State.ROUND_SINGLE : State.ROUND_MULTI;
        int nextTarget = nextTargetCount(currentTarget);
        currentTarget = nextTarget;
        spawnEntitiesToTarget(currentTarget, currentRound);
        if (handleHardCap(currentRound)) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () ->
            sampleMspt(SAMPLE_SECONDS, msptMedian -> handleSample(msptMedian, currentTarget)),
            STABILIZE_SECONDS * 20L
        );
    }

    private void handleSample(double msptMedian, int targetEntities) {
        if (handleHardCap(currentRound)) {
            return;
        }
        runData.addSample(currentRound, targetEntities, msptMedian);
        double baseline = runData.getBaseline(currentRound);
        boolean hitStop = msptMedian >= 40.0 || msptMedian >= baseline + 10.0;
        if (hitStop) {
            stopHits++;
        } else {
            stopHits = 0;
        }
        if (stopHits >= 2) {
            runData.setStopEntities(currentRound, targetEntities);
            clearEntities(benchmarkWorld);
            if (currentRound == Round.SINGLE) {
                state = State.CLEAN;
                currentRound = Round.MULTI;
                beginBaseline();
            } else {
                finalizeBenchmark();
            }
        } else {
            advanceRoundStep();
        }
    }

    private void finalizeBenchmark() {
        clearEntities(benchmarkWorld);
        state = State.SAVE;
        BenchmarkRecord record = runData.buildRecord();
        runData.lastRecord = record;
        lastRecord = record;
        try {
            storage.append(record);
        } catch (Exception ex) {
            plugin.getLogger().warning(langManager.get(
                "log.benchmark-save-failed",
                "error",
                ex.getMessage() == null ? langManager.get("common.unknown") : ex.getMessage()
            ));
        }
        reloadEffectiveThresholds();
        state = State.IDLE;
    }

    private void reloadEffectiveThresholds() {
        List<BenchmarkRecord> records = storage.load();
        if (records.isEmpty()) {
            effectiveThresholdSingle = 0.0;
            effectiveThresholdArea = 0.0;
            return;
        }
        lastRecord = records.get(records.size() - 1);
        double totalSingle = 0.0;
        double totalArea = 0.0;
        int count = 0;
        for (BenchmarkRecord record : records) {
            if (record.thresholdSingleChunk > 0 && record.thresholdArea > 0) {
                totalSingle += record.thresholdSingleChunk;
                totalArea += record.thresholdArea;
                count++;
            }
        }
        if (count == 0) {
            effectiveThresholdSingle = 0.0;
            effectiveThresholdArea = 0.0;
            return;
        }
        effectiveThresholdSingle = totalSingle / count;
        effectiveThresholdArea = totalArea / count;
    }

    private void abortRun() {
        if (samplingTask != null) {
            samplingTask.cancel();
            samplingTask = null;
        }
        if (delayedStartTask != null) {
            delayedStartTask.cancel();
            delayedStartTask = null;
        }
        state = State.IDLE;
        stopHits = 0;
    }

    private void sampleMspt(int seconds, Consumer<Double> callback) {
        if (samplingTask != null) {
            samplingTask.cancel();
        }
        List<Double> samples = new ArrayList<>();
        samplingTask = new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                if (ticks % 20 == 0) {
                    samples.add(getCurrentMspt());
                }
                ticks++;
                if (ticks >= seconds * 20) {
                    cancel();
                    samplingTask = null;
                    double median = median(samples);
                    callback.accept(median);
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private double getCurrentMspt() {
        return Bukkit.getServer().getAverageTickTime();
    }

    private double median(List<Double> samples) {
        if (samples.isEmpty()) {
            return 0.0;
        }
        samples.sort(Comparator.naturalOrder());
        int middle = samples.size() / 2;
        if (samples.size() % 2 == 1) {
            return samples.get(middle);
        }
        return (samples.get(middle - 1) + samples.get(middle)) / 2.0;
    }

    private int nextTargetCount(int current) {
        if (current == 0) {
            return INITIAL_STEPS[0];
        }
        for (int step : INITIAL_STEPS) {
            if (current == step) {
                int index = indexOfStep(step);
                if (index < INITIAL_STEPS.length - 1) {
                    return INITIAL_STEPS[index + 1];
                }
                break;
            }
        }
        return current + STEP_INCREMENT;
    }

    private int indexOfStep(int step) {
        for (int i = 0; i < INITIAL_STEPS.length; i++) {
            if (INITIAL_STEPS[i] == step) {
                return i;
            }
        }
        return -1;
    }

    private void spawnEntitiesToTarget(int target, Round round) {
        if (benchmarkWorld == null) {
            return;
        }
        int current = countEntities(benchmarkWorld);
        int toSpawn = target - current;
        if (toSpawn <= 0) {
            return;
        }
        int bees = (int) Math.round(toSpawn * 0.6);
        int items = (int) Math.round(toSpawn * 0.3);
        int expOrbs = toSpawn - bees - items;
        if (expOrbs < 0) {
            int overflow = -expOrbs;
            items = Math.max(0, items - overflow);
            expOrbs = toSpawn - bees - items;
            if (expOrbs < 0) {
                overflow = -expOrbs;
                bees = Math.max(0, bees - overflow);
                expOrbs = toSpawn - bees - items;
            }
        }

        spawnExtras(round, EntityType.BEE, bees);
        spawnItems(round, items);
        spawnExpOrbs(round, expOrbs);
        runData.addSpawned(toSpawn);
    }

    private void spawnExtras(Round round, EntityType type, int count) {
        for (int i = 0; i < count; i++) {
            Location loc = randomLocation(round);
            benchmarkWorld.spawnEntity(loc, type);
        }
    }

    private void spawnItems(Round round, int count) {
        ItemStack stack = new ItemStack(Material.COBBLESTONE);
        for (int i = 0; i < count; i++) {
            Location loc = randomLocation(round);
            Item item = benchmarkWorld.dropItem(loc, stack);
            item.setPickupDelay(Integer.MAX_VALUE);
        }
    }

    private void spawnExpOrbs(Round round, int count) {
        for (int i = 0; i < count; i++) {
            Location loc = randomLocation(round);
            ExperienceOrb orb = (ExperienceOrb) benchmarkWorld.spawnEntity(loc, EntityType.EXPERIENCE_ORB);
            orb.setExperience(1);
        }
    }

    private Location randomLocation(Round round) {
        int chunkX = round == Round.SINGLE ? 0 : random.nextInt(CHUNK_RADIUS * 2 + 1) - CHUNK_RADIUS;
        int chunkZ = round == Round.SINGLE ? 0 : random.nextInt(CHUNK_RADIUS * 2 + 1) - CHUNK_RADIUS;
        int blockX = chunkX * 16 + random.nextInt(16);
        int blockZ = chunkZ * 16 + random.nextInt(16);
        return new Location(benchmarkWorld, blockX + 0.5, GlassFlatGenerator.GLASS_Y + 1, blockZ + 0.5);
    }

    private void clearEntities(World world) {
        for (Entity entity : world.getEntities()) {
            if (!(entity instanceof Player)) {
                entity.remove();
            }
        }
    }

    private int countEntities(World world) {
        int count = 0;
        for (Entity entity : world.getEntities()) {
            if (!(entity instanceof Player)) {
                count++;
            }
        }
        return count;
    }

    private World ensureBenchmarkWorld() {
        World world = Bukkit.getWorld(BENCHMARK_WORLD_NAME);
        if (world == null) {
            WorldCreator creator = new WorldCreator(BENCHMARK_WORLD_NAME)
                .type(WorldType.FLAT)
                .generator(new GlassFlatGenerator())
                .generateStructures(false);
            world = Bukkit.createWorld(creator);
        }
        if (world != null) {
            world.setKeepSpawnInMemory(false);
            world.setSpawnLocation(0, GlassFlatGenerator.GLASS_Y + 1, 0);
            world.getWorldBorder().setCenter(0, 0);
            world.getWorldBorder().setSize((CHUNK_RADIUS * 2 + 1) * 16);
            world.setGameRule(GameRule.MAX_ENTITY_CRAMMING, 0);
            forceLoadChunks(world);
        }
        return world;
    }

    private boolean handleHardCap(Round round) {
        if (benchmarkWorld == null) {
            return false;
        }
        int current = countEntities(benchmarkWorld);
        if (current < HARD_CAP_ENTITIES) {
            return false;
        }
        runData.setStopEntities(round, current);
        runData.setCapReached();
        clearEntities(benchmarkWorld);
        finalizeBenchmark();
        return true;
    }

    private void forceLoadChunks(World world) {
        for (int x = -CHUNK_RADIUS; x <= CHUNK_RADIUS; x++) {
            for (int z = -CHUNK_RADIUS; z <= CHUNK_RADIUS; z++) {
                Chunk chunk = world.getChunkAt(x, z);
                chunk.setForceLoaded(true);
            }
        }
    }

    private boolean isChunkInBounds(int chunkX, int chunkZ) {
        return chunkX >= -CHUNK_RADIUS && chunkX <= CHUNK_RADIUS
            && chunkZ >= -CHUNK_RADIUS && chunkZ <= CHUNK_RADIUS;
    }

    private enum State {
        IDLE,
        WAIT_DELAY,
        CHECK_BASELINE,
        ROUND_SINGLE,
        CLEAN,
        ROUND_MULTI,
        SAVE
    }

    private enum Round {
        SINGLE,
        MULTI
    }

    private static class BenchmarkRunData {
        private final List<BenchmarkRecord.BenchmarkSample> samplesSingle = new ArrayList<>();
        private final List<BenchmarkRecord.BenchmarkSample> samplesMulti = new ArrayList<>();
        private double baselineSingle;
        private double baselineMulti;
        private int stopEntitiesSingle;
        private int stopEntitiesMulti;
        private int totalSpawned;
        private BenchmarkRecord lastRecord;
        private boolean capReached;

        void setBaseline(Round round, double baseline) {
            if (round == Round.SINGLE) {
                baselineSingle = baseline;
            } else {
                baselineMulti = baseline;
            }
        }

        double getBaseline(Round round) {
            return round == Round.SINGLE ? baselineSingle : baselineMulti;
        }

        void addSample(Round round, int entities, double mspt) {
            if (round == Round.SINGLE) {
                samplesSingle.add(new BenchmarkRecord.BenchmarkSample(entities, mspt));
            } else {
                samplesMulti.add(new BenchmarkRecord.BenchmarkSample(entities, mspt));
            }
        }

        void addSpawned(int count) {
            totalSpawned += count;
        }

        int getTotalSpawned() {
            return totalSpawned;
        }

        void setStopEntities(Round round, int entities) {
            if (round == Round.SINGLE) {
                stopEntitiesSingle = entities;
            } else {
                stopEntitiesMulti = entities;
            }
        }

        void setCapReached() {
            capReached = true;
        }

        BenchmarkRecord buildRecord() {
            BenchmarkRecord record = new BenchmarkRecord();
            record.timestamp = Instant.now().toString();
            record.baselineSingle = baselineSingle;
            record.baselineMulti = baselineMulti;
            record.samplesSingle = new ArrayList<>(samplesSingle);
            record.samplesMulti = new ArrayList<>(samplesMulti);
            record.stopEntitiesSingle = stopEntitiesSingle;
            record.stopEntitiesMulti = stopEntitiesMulti;
            record.capReached = capReached;
            if (capReached) {
                record.slopeSingle = 0.0;
                record.slopeMulti = 0.0;
                record.thresholdSingleChunk = BenchmarkManager.HARD_CAP_ENTITIES;
                record.thresholdArea = BenchmarkManager.HARD_CAP_ENTITIES;
                return record;
            }
            record.slopeSingle = calculateSlope(samplesSingle);
            record.slopeMulti = calculateSlope(samplesMulti);
            record.thresholdSingleChunk = calculateThreshold(baselineSingle, record.slopeSingle);
            record.thresholdArea = calculateThreshold(baselineMulti, record.slopeMulti);
            return record;
        }

        private double calculateSlope(List<BenchmarkRecord.BenchmarkSample> samples) {
            if (samples.size() < 2) {
                return 0.0;
            }
            double meanX = 0.0;
            double meanY = 0.0;
            for (BenchmarkRecord.BenchmarkSample sample : samples) {
                meanX += sample.entitiesTotal;
                meanY += sample.msptMedian;
            }
            meanX /= samples.size();
            meanY /= samples.size();
            double numerator = 0.0;
            double denominator = 0.0;
            for (BenchmarkRecord.BenchmarkSample sample : samples) {
                double dx = sample.entitiesTotal - meanX;
                double dy = sample.msptMedian - meanY;
                numerator += dx * dy;
                denominator += dx * dx;
            }
            if (denominator == 0.0) {
                return 0.0;
            }
            return numerator / denominator;
        }

        private double calculateThreshold(double baseline, double slope) {
            if (slope <= 0.0) {
                return 0.0;
            }
            double targetMspt = Math.min(40.0, baseline + 12.0);
            double margin = targetMspt - baseline;
            double capacity = margin / slope;
            return Math.max(0.0, capacity * 0.65);
        }
    }
}
