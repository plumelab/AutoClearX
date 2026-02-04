package fengzhiyu.top.autoClearX;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public class SweepManager {
    private static final int CHUNKS_PER_TICK = 5;
    private static final int QUEUE_RADIUS = 4;
    private static final Duration QUEUE_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration STAT_TIMEOUT = Duration.ofSeconds(30);
    private static final int COUNTDOWN_SECONDS = 60;
    private static final int COUNTDOWN_RECHECK = 10;
    private static final int NOTIFY_RADIUS_BLOCKS = 64;
    private final JavaPlugin plugin;
    private final BenchmarkManager benchmarkManager;
    private final LangManager langManager;
    private final Set<Material> itemWhitelist;
    private final Set<EntityType> entityWhitelist;
    private final List<ChunkOffset> offsets;
    private final Map<ChunkKey, ChunkStat> stats = new HashMap<>();
    private final Map<ChunkKey, Long> queueTouched = new HashMap<>();
    private final Set<ChunkKey> inQueue = new HashSet<>();
    private final Deque<ChunkKey> queue = new ArrayDeque<>();
    private final Inventory trashInventory;
    private BukkitTask sweepTask;
    private BukkitTask announceTask;
    private BukkitTask countdownTask;
    private int trashIndex = 0;
    private long globalCount = 0;
    private State state = State.IDLE;
    private ChunkKey targetChunk;
    private int countdownRemaining = 0;

    public SweepManager(JavaPlugin plugin, BenchmarkManager benchmarkManager, LangManager langManager) {
        this.plugin = plugin;
        this.benchmarkManager = benchmarkManager;
        this.langManager = langManager;
        this.itemWhitelist = loadItemWhitelist(plugin);
        this.entityWhitelist = loadEntityWhitelist(plugin);
        this.offsets = buildOffsets(QUEUE_RADIUS);
        this.trashInventory = Bukkit.createInventory(null, 54, langManager.get("sweep.trash.title"));
    }

    public void start() {
        if (sweepTask == null) {
            sweepTask = Bukkit.getScheduler().runTaskTimer(plugin, this::runSweepTick, 10L, 10L);
        }
        if (announceTask == null) {
            announceTask = Bukkit.getScheduler().runTaskTimer(plugin, this::broadcastStatus, 20L * 60L, 20L * 60L);
        }
    }

    public void shutdown() {
        if (sweepTask != null) {
            sweepTask.cancel();
            sweepTask = null;
        }
        if (announceTask != null) {
            announceTask.cancel();
            announceTask = null;
        }
        cancelCountdown(false);
    }

    public SweepStatus getStatus() {
        Thresholds thresholds = calculateThresholds();
        ChunkSummary top = findTopChunk();
        return new SweepStatus(
            state,
            targetChunk,
            countdownRemaining,
            globalCount,
            thresholds,
            top,
            formatTargetText(targetChunk),
            formatTopText(top)
        );
    }

    public boolean triggerImmediateSweep() {
        if (state == State.COUNTDOWN) {
            cancelCountdown(false);
        }
        Thresholds thresholds = calculateThresholds();
        if (!thresholds.isValid()) {
            return false;
        }
        ChunkSummary top = findTopChunk();
        if (top == null) {
            return false;
        }
        targetChunk = top.key();
        performCleanup();
        return true;
    }

    public Inventory getTrashInventory() {
        return trashInventory;
    }

    private void runSweepTick() {
        refreshQueueFromPlayers();
        pruneExpiredStats();
        for (int i = 0; i < CHUNKS_PER_TICK; i++) {
            processQueueStep();
        }
    }

    private void refreshQueueFromPlayers() {
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            World world = player.getWorld();
            Chunk base = player.getLocation().getChunk();
            for (ChunkOffset offset : offsets) {
                int chunkX = base.getX() + offset.dx();
                int chunkZ = base.getZ() + offset.dz();
                if (!world.isChunkLoaded(chunkX, chunkZ)) {
                    continue;
                }
                ChunkKey key = new ChunkKey(world.getName(), chunkX, chunkZ);
                queueTouched.put(key, now);
                if (!inQueue.contains(key)) {
                    inQueue.add(key);
                    queue.addLast(key);
                }
            }
        }
    }

    private void processQueueStep() {
        while (!queue.isEmpty()) {
            ChunkKey key = queue.pollFirst();
            inQueue.remove(key);
            Long touched = queueTouched.get(key);
            if (touched == null) {
                continue;
            }
            if (isExpired(touched, QUEUE_TIMEOUT)) {
                queueTouched.remove(key);
                continue;
            }
            World world = Bukkit.getWorld(key.worldName());
            if (world == null || !world.isChunkLoaded(key.chunkX(), key.chunkZ())) {
                queueTouched.remove(key);
                return;
            }
            Chunk chunk = world.getChunkAt(key.chunkX(), key.chunkZ());
            int count = countChunkEntities(chunk);
            updateChunkStat(key, count);
            queue.addLast(key);
            inQueue.add(key);
            checkThresholdsAfterUpdate();
            return;
        }
    }

    private void updateChunkStat(ChunkKey key, int count) {
        ChunkStat previous = stats.get(key);
        if (previous != null) {
            globalCount -= previous.count();
        }
        stats.put(key, new ChunkStat(count, System.currentTimeMillis()));
        globalCount += count;
    }

    private void pruneExpiredStats() {
        Iterator<Map.Entry<ChunkKey, ChunkStat>> iterator = stats.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<ChunkKey, ChunkStat> entry = iterator.next();
            ChunkKey key = entry.getKey();
            ChunkStat stat = entry.getValue();
            if (isExpired(stat.lastUpdate(), STAT_TIMEOUT)) {
                globalCount -= stat.count();
                iterator.remove();
                continue;
            }
            World world = Bukkit.getWorld(key.worldName());
            if (world == null || !world.isChunkLoaded(key.chunkX(), key.chunkZ())) {
                globalCount -= stat.count();
                iterator.remove();
            }
        }
    }

    private void checkThresholdsAfterUpdate() {
        if (state != State.IDLE) {
            return;
        }
        Thresholds thresholds = calculateThresholds();
        if (!thresholds.isValid()) {
            return;
        }
        ChunkSummary top = findTopChunk();
        if (top == null) {
            return;
        }
        if (globalCount >= thresholds.globalTrigger() || top.count() >= thresholds.chunkTrigger()) {
            startCountdown(top.key());
        }
    }

    private void startCountdown(ChunkKey key) {
        if (state != State.IDLE) {
            return;
        }
        targetChunk = key;
        state = State.COUNTDOWN;
        countdownRemaining = COUNTDOWN_SECONDS;
        notifyNearbyPlayers(key, langManager.get(
            "sweep.notify.countdown-start",
            "seconds",
            String.valueOf(COUNTDOWN_SECONDS)
        ));
        countdownTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            countdownRemaining--;
            if (countdownRemaining == COUNTDOWN_RECHECK) {
                if (shouldCancelCountdown()) {
                    notifyNearbyPlayers(targetChunk, langManager.get("sweep.notify.countdown-cancel"));
                    cancelCountdown(false);
                    return;
                }
            }
            if (countdownRemaining <= 0) {
                performCleanup();
            }
        }, 20L, 20L);
    }

    private boolean shouldCancelCountdown() {
        if (targetChunk == null) {
            return true;
        }
        World world = Bukkit.getWorld(targetChunk.worldName());
        if (world == null || !world.isChunkLoaded(targetChunk.chunkX(), targetChunk.chunkZ())) {
            return true;
        }
        int count = countChunkEntities(world.getChunkAt(targetChunk.chunkX(), targetChunk.chunkZ()));
        updateChunkStat(targetChunk, count);
        Thresholds thresholds = calculateThresholds();
        if (!thresholds.isValid()) {
            return true;
        }
        return globalCount < thresholds.globalTrigger() && count < thresholds.chunkTrigger();
    }

    private void performCleanup() {
        if (targetChunk == null) {
            cancelCountdown(false);
            return;
        }
        state = State.CLEANING;
        World world = Bukkit.getWorld(targetChunk.worldName());
        if (world == null || !world.isChunkLoaded(targetChunk.chunkX(), targetChunk.chunkZ())) {
            cancelCountdown(false);
            state = State.IDLE;
            return;
        }
        Chunk chunk = world.getChunkAt(targetChunk.chunkX(), targetChunk.chunkZ());
        List<ItemStack> collected = new ArrayList<>();
        for (Entity entity : chunk.getEntities()) {
            if (entity instanceof Item item) {
                ItemStack stack = item.getItemStack();
                if (isWhitelistedItem(stack)) {
                    continue;
                }
                collected.add(stack.clone());
                item.remove();
            } else if (entity instanceof LivingEntity living) {
                if (living instanceof Player) {
                    continue;
                }
                if (living.getCustomName() != null) {
                    continue;
                }
                if (entityWhitelist.contains(living.getType())) {
                    continue;
                }
                living.remove();
            }
        }
        for (ItemStack stack : collected) {
            addToTrash(stack);
        }
        int count = countChunkEntities(chunk);
        updateChunkStat(targetChunk, count);
        notifyNearbyPlayers(targetChunk, langManager.get("sweep.notify.cleanup-done"));
        cancelCountdown(false);
        state = State.IDLE;
    }

    private void cancelCountdown(boolean keepTarget) {
        if (countdownTask != null) {
            countdownTask.cancel();
            countdownTask = null;
        }
        countdownRemaining = 0;
        state = State.IDLE;
        if (!keepTarget) {
            targetChunk = null;
        }
    }

    private void notifyNearbyPlayers(ChunkKey key, String message) {
        if (key == null) {
            return;
        }
        World world = Bukkit.getWorld(key.worldName());
        if (world == null) {
            return;
        }
        int centerX = key.chunkX() * 16 + 8;
        int centerZ = key.chunkZ() * 16 + 8;
        int radius = NOTIFY_RADIUS_BLOCKS;
        int radiusSq = radius * radius;
        for (Player player : world.getPlayers()) {
            Location loc = player.getLocation();
            int dx = loc.getBlockX() - centerX;
            int dz = loc.getBlockZ() - centerZ;
            if (dx * dx + dz * dz <= radiusSq) {
                player.sendMessage(message);
            }
        }
    }

    private void broadcastStatus() {
        Thresholds thresholds = calculateThresholds();
        ChunkSummary top = findTopChunk();
        String globalTrigger = thresholds.isValid()
            ? String.format("%.2f", thresholds.globalTrigger())
            : langManager.get("sweep.broadcast.unavailable");
        String chunkTrigger = thresholds.isValid()
            ? String.format("%.2f", thresholds.chunkTrigger())
            : langManager.get("sweep.broadcast.unavailable");
        String topWorld = top == null ? langManager.get("common.none") : top.key().worldName();
        String topCoords = top == null ? langManager.get("common.none") : top.key().chunkX() + "," + top.key().chunkZ();
        String topCount = top == null ? "0" : String.valueOf(top.count());
        String stateText = langManager.get("sweep.state." + state.name().toLowerCase());
        String actionBar = langManager.get(
            "sweep.broadcast.actionbar",
            "globalCount",
            String.valueOf(globalCount),
            "globalTrigger",
            globalTrigger,
            "topWorld",
            topWorld,
            "topCoords",
            topCoords,
            "topCount",
            topCount,
            "chunkTrigger",
            chunkTrigger,
            "state",
            stateText
        );
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendActionBar(actionBar);
        }
    }

    private int countChunkEntities(Chunk chunk) {
        int count = 0;
        for (Entity entity : chunk.getEntities()) {
            if (entity instanceof Item item) {
                if (isWhitelistedItem(item.getItemStack())) {
                    continue;
                }
                count++;
            } else if (entity instanceof LivingEntity living) {
                if (living instanceof Player) {
                    continue;
                }
                if (living.getCustomName() != null) {
                    continue;
                }
                if (entityWhitelist.contains(living.getType())) {
                    continue;
                }
                count++;
            }
        }
        return count;
    }

    private boolean isWhitelistedItem(ItemStack stack) {
        return itemWhitelist.contains(stack.getType());
    }

    private void addToTrash(ItemStack stack) {
        ItemStack remaining = stack.clone();
        int size = trashInventory.getSize();
        for (int i = 0; i < size; i++) {
            int slot = (trashIndex + i) % size;
            ItemStack existing = trashInventory.getItem(slot);
            if (existing == null || existing.getType() == Material.AIR) {
                trashInventory.setItem(slot, remaining);
                trashIndex = (slot + 1) % size;
                return;
            }
            if (!existing.isSimilar(remaining)) {
                continue;
            }
            int max = existing.getMaxStackSize();
            int space = max - existing.getAmount();
            if (space <= 0) {
                continue;
            }
            int toMove = Math.min(space, remaining.getAmount());
            existing.setAmount(existing.getAmount() + toMove);
            remaining.setAmount(remaining.getAmount() - toMove);
            trashInventory.setItem(slot, existing);
            if (remaining.getAmount() <= 0) {
                trashIndex = (slot + 1) % size;
                return;
            }
        }
        trashInventory.setItem(trashIndex, remaining);
        trashIndex = (trashIndex + 1) % size;
    }

    private Thresholds calculateThresholds() {
        double effectiveSingle = benchmarkManager.getEffectiveThresholdSingle();
        double effectiveArea = benchmarkManager.getEffectiveThresholdArea();
        if (effectiveSingle <= 0.0 || effectiveArea <= 0.0) {
            return Thresholds.invalid();
        }
        int hardCap = benchmarkManager.getHardCapEntities();
        if (effectiveArea * 0.6 > hardCap * 0.6 || effectiveSingle * 0.3 > hardCap * 0.3) {
            return new Thresholds(hardCap, hardCap);
        }
        return new Thresholds(effectiveArea, effectiveSingle);
    }

    private ChunkSummary findTopChunk() {
        ChunkSummary best = null;
        for (Map.Entry<ChunkKey, ChunkStat> entry : stats.entrySet()) {
            if (best == null || entry.getValue().count() > best.count()) {
                best = new ChunkSummary(entry.getKey(), entry.getValue().count());
            }
        }
        return best;
    }

    private boolean isExpired(long timestamp, Duration timeout) {
        return System.currentTimeMillis() - timestamp > timeout.toMillis();
    }

    private static List<ChunkOffset> buildOffsets(int radius) {
        List<ChunkOffset> results = new ArrayList<>();
        results.add(new ChunkOffset(0, 0));
        for (int r = 1; r <= radius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                results.add(new ChunkOffset(dx, -r));
            }
            for (int dz = -r + 1; dz <= r; dz++) {
                results.add(new ChunkOffset(r, dz));
            }
            for (int dx = r - 1; dx >= -r; dx--) {
                results.add(new ChunkOffset(dx, r));
            }
            for (int dz = r - 1; dz >= -r + 1; dz--) {
                results.add(new ChunkOffset(-r, dz));
            }
        }
        return results;
    }

    private Set<Material> loadItemWhitelist(JavaPlugin plugin) {
        Set<Material> materials = new HashSet<>();
        List<String> entries = plugin.getConfig().getStringList("itemMaterialWhitelist");
        for (String entry : entries) {
            Material material = Material.matchMaterial(entry);
            if (material == null) {
                plugin.getLogger().warning(langManager.get("log.invalid-material", "material", entry));
                continue;
            }
            materials.add(material);
        }
        return materials;
    }

    private Set<EntityType> loadEntityWhitelist(JavaPlugin plugin) {
        Set<EntityType> types = new HashSet<>();
        List<String> entries = plugin.getConfig().getStringList("entityTypeWhitelist");
        for (String entry : entries) {
            try {
                types.add(EntityType.valueOf(entry.toUpperCase()));
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning(langManager.get("log.invalid-entity-type", "entity", entry));
            }
        }
        return types;
    }

    public enum State {
        IDLE,
        COUNTDOWN,
        CLEANING
    }

    private record ChunkKey(String worldName, int chunkX, int chunkZ) {
        String toShortString() {
            return worldName + ":" + chunkX + "," + chunkZ;
        }
    }

    private record ChunkStat(int count, long lastUpdate) {}

    private record ChunkSummary(ChunkKey key, int count) {}

    private record ChunkOffset(int dx, int dz) {}

    public record Thresholds(double globalThreshold, double chunkThreshold) {
        double globalTrigger() {
            return globalThreshold * 0.6;
        }

        double chunkTrigger() {
            return chunkThreshold * 0.3;
        }

        boolean isValid() {
            return globalThreshold > 0.0 && chunkThreshold > 0.0;
        }

        static Thresholds invalid() {
            return new Thresholds(0.0, 0.0);
        }
    }

    public record SweepStatus(
        State state,
        ChunkKey target,
        int countdown,
        long globalCount,
        Thresholds thresholds,
        ChunkSummary topChunk,
        String targetText,
        String topText
    ) {}

    private String formatTargetText(ChunkKey key) {
        return key == null ? langManager.get("common.none") : key.toShortString();
    }

    private String formatTopText(ChunkSummary summary) {
        if (summary == null) {
            return langManager.get("common.none");
        }
        return summary.key().toShortString() + " (" + summary.count() + ")";
    }
}
