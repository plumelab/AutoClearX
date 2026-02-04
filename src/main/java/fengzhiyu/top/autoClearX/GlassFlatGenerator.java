package fengzhiyu.top.autoClearX;

import java.util.Random;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.generator.ChunkGenerator;

public class GlassFlatGenerator extends ChunkGenerator {
    public static final int GLASS_Y = 64;

    @Override
    public ChunkData generateChunkData(World world, Random random, int chunkX, int chunkZ, BiomeGrid biome) {
        ChunkData chunkData = createChunkData(world);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                chunkData.setBlock(x, GLASS_Y, z, Material.GLASS);
            }
        }
        return chunkData;
    }
}
