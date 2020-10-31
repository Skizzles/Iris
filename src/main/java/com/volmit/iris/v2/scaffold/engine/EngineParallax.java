package com.volmit.iris.v2.scaffold.engine;

import com.volmit.iris.Iris;
import com.volmit.iris.gen.ContextualTerrainProvider;
import com.volmit.iris.manager.IrisDataManager;
import com.volmit.iris.object.*;
import com.volmit.iris.util.*;
import com.volmit.iris.v2.generator.IrisComplex;
import com.volmit.iris.v2.generator.IrisEngine;
import com.volmit.iris.v2.scaffold.cache.Cache;
import com.volmit.iris.v2.scaffold.data.DataProvider;
import com.volmit.iris.v2.scaffold.hunk.Hunk;
import com.volmit.iris.v2.scaffold.parallax.ParallaxAccess;
import com.volmit.iris.v2.scaffold.parallel.BurstExecutor;
import com.volmit.iris.v2.scaffold.parallel.MultiBurst;
import org.bukkit.block.data.BlockData;
import org.bukkit.util.BlockVector;

import java.util.concurrent.atomic.AtomicInteger;

public interface EngineParallax extends DataProvider, IObjectPlacer
{
    public static final BlockData AIR = B.get("AIR");

    public Engine getEngine();

    public int getParallaxSize();

    default EngineFramework getFramework()
    {
        return getEngine().getFramework();
    }

    default ParallaxAccess getParallaxAccess()
    {
        return getEngine().getParallax();
    }

    default IrisDataManager getData()
    {
        return getEngine().getData();
    }

    default IrisComplex getComplex()
    {
        return getEngine().getFramework().getComplex();
    }

    default KList<IrisRegion> getAllRegions()
    {
        KList<IrisRegion> r = new KList<>();

        for(String i : getEngine().getDimension().getRegions())
        {
            r.add(getEngine().getData().getRegionLoader().load(i));
        }

        return r;
    }

    default KList<IrisBiome> getAllBiomes()
    {
        KList<IrisBiome> r = new KList<>();

        for(IrisRegion i : getAllRegions())
        {
            r.addAll(i.getAllBiomes(this));
        }

        return r;
    }

    default void insertParallax(int x, int z, Hunk<BlockData> data)
    {
        data.compute3D(getEngine().getParallelism(), (xx,yy,zz,h)->{
            for(int i = x+xx; i < x+xx+ h.getWidth(); i++)
            {
                for(int j= z+zz; j < z+zz + h.getDepth(); j++)
                {
                    for(int k= yy; k < yy+h.getHeight(); k++)
                    {
                        BlockData d = getParallaxAccess().getBlock(i, k, j);

                        if(d != null)
                        {
                            h.set(i - (x-xx), k-yy, j - (z+zz), d);
                        }
                    }
                }
            }
        });
    }

    default void generateParallaxArea(int x, int z)
    {
        int s = (int) Math.ceil(getParallaxSize() / 2D);
        for(int i = -s; i <= s; i++)
        {
            for(int j = -s; j <= s; j++)
            {
                generateParallaxLayer((i*16)+x, (j*16)+z);
            }
        }

        getParallaxAccess().setChunkGenerated(x>>4, z>>4);
    }

    default void generateParallaxLayer(int x, int z)
    {
        if(getParallaxAccess().isParallaxGenerated(x >> 4, z >> 4))
        {
            return;
        }

        getParallaxAccess().setParallaxGenerated(x>>4, z>>4);
        RNG rng = new RNG(Cache.key(x, z)).nextParallelRNG(getEngine().getTarget().getWorld().getSeed());
        generateParallaxSurface(rng, x, z);
    }

    default void generateParallaxSurface(RNG rng, int x, int z) {
        IrisBiome biome = getComplex().getTrueBiomeStream().get(x + 8, z + 8);

        for (IrisObjectPlacement i : biome.getSurfaceObjects())
        {
            if(rng.chance(i.getChance()))
            {
                place(rng, x, z, i);
            }
        }
    }

    default void place(RNG rng, int x, int z, IrisObjectPlacement objectPlacement)
    {
        for(int i = 0; i < objectPlacement.getDensity(); i++)
        {
            objectPlacement.getSchematic(getComplex(), rng).place(rng.i(x, x+16), rng.i(z, z+16), this, objectPlacement, rng, getData());
        }
    }

    default int computeParallaxSize()
    {
        Iris.verbose("Calculating the Parallax Size in Parallel");
        AtomicInteger xg = new AtomicInteger(0);
        AtomicInteger zg = new AtomicInteger();
        xg.set(0);
        zg.set(0);

        KSet<String> objects = new KSet<>();
        KList<IrisRegion> r = getAllRegions();
        KList<IrisBiome> b = getAllBiomes();

        for(IrisBiome i : b)
        {
            for(IrisObjectPlacement j : i.getObjects())
            {
                objects.addAll(j.getPlace());
            }
        }

        IrisLock t = new IrisLock("t");
        Iris.verbose("Checking sizes for " + Form.f(objects.size()) + " referenced objects.");
        BurstExecutor e = MultiBurst.burst.burst(objects.size());
        for(String i : objects)
        {
            e.queue(() -> {
                try
                {
                    BlockVector bv = IrisObject.sampleSize(getData().getObjectLoader().findFile(i));
                    synchronized (xg)
                    {
                        xg.getAndSet(Math.max(bv.getBlockX(), xg.get()));
                    }

                    synchronized (zg)
                    {
                        zg.getAndSet(Math.max(bv.getBlockZ(), zg.get()));
                    }
                }

                catch(Throwable ignored)
                {

                }
            });
        }

        e.complete();

        int x = xg.get();
        int z = zg.get();

        for(IrisDepositGenerator i : getEngine().getDimension().getDeposits())
        {
            int max = i.getMaxDimension();
            x = Math.max(max, x);
            z = Math.max(max, z);
        }

        for(IrisTextPlacement i : getEngine().getDimension().getText())
        {
            int max = i.maxDimension();
            x = Math.max(max, x);
            z = Math.max(max, z);
        }

        for(IrisRegion v : r)
        {
            for(IrisDepositGenerator i : v.getDeposits())
            {
                int max = i.getMaxDimension();
                x = Math.max(max, x);
                z = Math.max(max, z);
            }

            for(IrisTextPlacement i : v.getText())
            {
                int max = i.maxDimension();
                x = Math.max(max, x);
                z = Math.max(max, z);
            }
        }

        for(IrisBiome v : b)
        {
            for(IrisDepositGenerator i : v.getDeposits())
            {
                int max = i.getMaxDimension();
                x = Math.max(max, x);
                z = Math.max(max, z);
            }

            for(IrisTextPlacement i : v.getText())
            {
                int max = i.maxDimension();
                x = Math.max(max, x);
                z = Math.max(max, z);
            }
        }

        x = (Math.max(x, 16) + 16) >> 4;
        z = (Math.max(z, 16) + 16) >> 4;
        x = x % 2 == 0 ? x + 1 : x;
        z = z % 2 == 0 ? z + 1 : z;
        x = Math.max(x, z);
        z = x;
        Iris.verbose(getEngine().getDimension().getName() + " Parallax Size: " + x + ", " + z);
        return x;
    }

    @Override
    default int getHighest(int x, int z) {
        return getHighest(x,z,false);
    }

    @Override
    default int getHighest(int x, int z, boolean ignoreFluid) {
        return (int) Math.round(ignoreFluid ? getComplex().getHeightStream().get(x, z) : getComplex().getHeightFluidStream().get(x, z));
    }

    @Override
    default void set(int x, int y, int z, BlockData d) {
        getParallaxAccess().setBlock(x,y,z,d);
    }

    @Override
    default BlockData get(int x, int y, int z) {
        BlockData block = getParallaxAccess().getBlock(x,y,z);

        if(block == null)
        {
            return AIR;
        }

        return block;
    }

    @Override
    default boolean isPreventingDecay() {
        return getEngine().getDimension().isPreventLeafDecay();
    }

    @Override
    default boolean isSolid(int x, int y, int z) {
        return B.isSolid(get(x,y,z));
    }

    @Override
    default boolean isUnderwater(int x, int z) {
        return getHighest(x, z, true) <= getFluidHeight();
    }

    @Override
    default int getFluidHeight() {
        return getEngine().getDimension().getFluidHeight();
    }

    @Override
    default boolean isDebugSmartBore() {
        return getEngine().getDimension().isDebugSmartBore();
    }
}
