package com.truesim.effects.water;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.procedural.water.Channel;
import com.truesim.common.procedural.water.WaterField;
import com.truesim.common.procedural.water.WaterNetwork;
import com.truesim.common.procedural.water.WaterRegion;
import com.truesim.common.procedural.water.Waterfall;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.VulkanDevice;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Where a water network wets the ground, as a map the flow hook reads on the GPU (flow.slang's {@code wetness}): every
 * surface that is not water is darkened and glossed where it lies within it, the banks in a band above the water's
 * edge (and the stones in it), the rock a fall runs down from its lip to its foot.
 *
 * <p>Sparse: tiles of {@code tileCells} x {@code tileCells} cells, only those the water reaches; per cell the top and
 * bottom of the wet band as float16 above the tile's base height. Laid out as flow.slang's {@code WetHeader} says:
 * the header, a uint per tile of the region (0: dry, else its number plus 1), a float base per wet tile, a uint per
 * cell.
 *
 * <pre>{@code
 * WaterWetness wet = WaterWetness.of(network, settings);   // built once, on the CPU
 * WaterFlow flow = new WaterFlow(view).wetness(wet.upload(device));   // the hook reads it
 * }</pre>
 */
public final class WaterWetness {

    private static final ValueLayout.OfInt U32 = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfFloat F32 = ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    /** The header's bytes. */
    static final int HEADER = 48;

    private final double x0;
    private final double z0;
    private final double cell;
    private final int cells;
    private final int tilesX;
    private final int tilesZ;
    private final Map<Integer, float[]> tops = new LinkedHashMap<>();
    private final Map<Integer, float[]> bottoms = new LinkedHashMap<>();

    private WaterWetness(WaterRegion region, double cell, int cells) {
        this.x0 = region.x0();
        this.z0 = region.z0();
        this.cell = cell;
        this.cells = cells;
        double tile = cell * cells;
        this.tilesX = (int) Math.ceil(region.width() / tile);
        this.tilesZ = (int) Math.ceil(region.depth() / tile);
    }

    /** {@code network}'s wet ground as {@link WaterRenderSettings} say (band height, cell). */
    @AllowAllocation("built once, on the CPU")
    public static WaterWetness of(WaterNetwork network, com.truesim.engine.settings.EngineSettings settings) {
        return of(network, settings.get(WaterRenderSettings.WET_HEIGHT), settings.get(WaterRenderSettings.WET_CELL),
            16);
    }

    /**
     * {@code network}'s wet ground: banks up to {@code height} metres above the water's edge, falls' rock from lip to
     * foot, on cells {@code cell} metres wide in tiles of {@code cells} a side.
     */
    @AllowAllocation("built once, on the CPU")
    public static WaterWetness of(WaterNetwork network, double height, double cell, int cells) {
        if (!(height >= 0) || !(cell > 0) || cells < 1) {
            throw new IllegalArgumentException("A wet band's height, a cell and cells a tile: " + height + ", " + cell
                + ", " + cells);
        }
        WaterWetness w = new WaterWetness(network.region(), cell, cells);
        WaterField field = network.field();
        for (Channel c : network.channels()) {
            for (int i = 0; i + 1 < c.count(); i++) {
                if (c.fall(i) >= 0) {
                    continue;
                }
                double reach = 0.5 * Math.max(c.width(i), c.width(i + 1)) + 2 * cell + 1;
                double length = c.distance(i + 1) - c.distance(i);
                int steps = Math.max(1, (int) Math.ceil(length / cell));
                for (int k = 0; k <= steps; k++) {
                    double t = (double) k / steps;
                    double cx = c.x(i) + t * (c.x(i + 1) - c.x(i));
                    double cz = c.z(i) + t * (c.z(i + 1) - c.z(i));
                    double surface = c.surface(i) + t * (c.surface(i + 1) - c.surface(i));
                    double rx = -c.directionZ(i);
                    double rz = c.directionX(i);
                    for (double o = -reach; o <= reach; o += cell * 0.5) {
                        double px = cx + rx * o;
                        double pz = cz + rz * o;
                        WaterField.Sample s = field.sample(px, pz);
                        if (s != null && s.distance() <= s.halfWidth() + 2 * cell + 1) {
                            w.wet(px, pz, surface + height, surface - 2);
                        }
                    }
                }
            }
        }
        for (Waterfall f : network.falls()) {
            if (!f.free()) {
                continue;
            }
            Waterfall.Path path = f.path();
            double top = path.y()[0] + 0.5;
            double bottom = path.y()[path.count() - 1] - 2;
            for (int k = 0; k < path.count(); k++) {
                double half = 0.5 * path.width()[k] + cell;
                double dx = path.vx()[k];
                double dz = path.vz()[k];
                double l = Math.sqrt(dx * dx + dz * dz);
                double rx = l > 1e-6 ? -dz / l : 1;
                double rz = l > 1e-6 ? dx / l : 0;
                // the rock behind the sheet: across it, and a few metres back into the face
                for (double back = -2; back <= 6; back += cell * 0.5) {
                    for (double o = -half; o <= half; o += cell * 0.5) {
                        double ux = l > 1e-6 ? dx / l : 0;
                        double uz = l > 1e-6 ? dz / l : 0;
                        w.wet(path.x()[k] + rx * o - ux * back, path.z()[k] + rz * o - uz * back, top, bottom);
                    }
                }
            }
        }
        return w;
    }

    /** Wets the cell at ({@code x}, {@code z}) from {@code bottom} to {@code top}, widening what it had. */
    @AllowAllocation("built once, on the CPU")
    private void wet(double x, double z, double top, double bottom) {
        double tile = cell * cells;
        int ti = (int) Math.floor((x - x0) / tile);
        int tj = (int) Math.floor((z - z0) / tile);
        if (ti < 0 || tj < 0 || ti >= tilesX || tj >= tilesZ) {
            return;
        }
        int key = tj * tilesX + ti;
        float[] t = tops.computeIfAbsent(key, k -> filled(Float.NEGATIVE_INFINITY));
        float[] b = bottoms.computeIfAbsent(key, k -> filled(Float.POSITIVE_INFINITY));
        int ci = Math.min(cells - 1, (int) ((x - x0 - ti * tile) / cell));
        int cj = Math.min(cells - 1, (int) ((z - z0 - tj * tile) / cell));
        int c = cj * cells + ci;
        t[c] = (float) Math.max(t[c], top);
        b[c] = (float) Math.min(b[c], bottom);
    }

    private float[] filled(float value) {
        float[] a = new float[cells * cells];
        Arrays.fill(a, value);
        return a;
    }

    /** How many tiles are wet. */
    public int tiles() {
        return tops.size();
    }

    /** Whether the cell at ({@code x}, {@code z}) is wet at height {@code y}. */
    @AllowAllocation("a query of the CPU map, for tests and tools")
    public boolean wet(double x, double y, double z) {
        double tile = cell * cells;
        int ti = (int) Math.floor((x - x0) / tile);
        int tj = (int) Math.floor((z - z0) / tile);
        float[] t = tops.get(tj * tilesX + ti);
        if (ti < 0 || tj < 0 || ti >= tilesX || tj >= tilesZ || t == null) {
            return false;
        }
        int c = Math.min(cells - 1, (int) ((z - z0 - tj * tile) / cell)) * cells
            + Math.min(cells - 1, (int) ((x - x0 - ti * tile) / cell));
        return y <= t[c] && y >= bottoms.get(tj * tilesX + ti)[c];
    }

    /** The map's bytes, as flow.slang reads them. */
    @AllowAllocation("built once, on the CPU")
    public MemorySegment bytes() {
        int count = tilesX * tilesZ;
        int tiles = tops.size();
        long index = HEADER;
        long bases = index + 4L * count;
        long data = bases + 4L * tiles;
        long size = data + 4L * tiles * cells * cells;
        MemorySegment m = Arena.ofAuto().allocate(size, 8);
        m.set(F32, 0, (float) x0);
        m.set(F32, 4, (float) z0);
        m.set(F32, 8, (float) cell);
        m.set(F32, 12, (float) (cell * cells));
        m.set(U32, 16, tilesX);
        m.set(U32, 20, tilesZ);
        m.set(U32, 24, cells);
        m.set(U32, 28, (int) index);
        m.set(U32, 32, (int) bases);
        m.set(U32, 36, (int) data);
        int number = 0;
        for (Map.Entry<Integer, float[]> e : tops.entrySet()) {
            float[] t = e.getValue();
            float[] b = bottoms.get(e.getKey());
            float base = Float.POSITIVE_INFINITY;
            for (int c = 0; c < t.length; c++) {
                if (t[c] > Float.NEGATIVE_INFINITY) {
                    base = Math.min(base, b[c]);
                }
            }
            m.set(U32, index + 4L * e.getKey(), number + 1);
            m.set(F32, bases + 4L * number, base);
            for (int c = 0; c < t.length; c++) {
                int word = 0;
                if (t[c] > Float.NEGATIVE_INFINITY) {
                    int top = Float.floatToFloat16(t[c] - base) & 0xffff;
                    int bottom = Float.floatToFloat16(b[c] - base) & 0xffff;
                    word = top | bottom << 16;
                    if (word == 0) {
                        word = 1; // a cell wet at its base alone still counts
                    }
                }
                m.set(U32, data + 4L * ((long) number * cells * cells + c), word);
            }
            number++;
        }
        return m;
    }

    /** The map in a GPU buffer the hook reads by its address ({@link GpuBuffer#deviceAddress()}); the caller closes it. */
    @AllowAllocation("uploaded once")
    public GpuBuffer upload(VulkanDevice device) {
        MemorySegment bytes = bytes();
        GpuBuffer buffer = GpuBuffer.hostVisible(device, bytes.byteSize(), VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
            | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT);
        MemorySegment.copy(bytes, 0, buffer.memory(), 0, bytes.byteSize());
        return buffer;
    }
}
