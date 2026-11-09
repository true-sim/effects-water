package com.truesim.effects.water;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.truesim.common.procedural.assets.GenerationCache;
import com.truesim.common.procedural.assets.ProceduralAssets;
import com.truesim.common.procedural.assets.WaterAssets;
import com.truesim.common.procedural.water.WaterNetwork;
import com.truesim.common.procedural.water.WaterParameters;
import com.truesim.common.state.ChangeClock;
import com.truesim.effects.atmosphere.fog.FogVolume;
import com.truesim.effects.water.materials.FallingWaterParameters;
import com.truesim.effects.water.materials.FlowingWaterParameters;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.scene.assets.AssetCache;
import com.truesim.scene.assets.PathAssets;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.render.Renderable;
import java.nio.file.Path;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The parts' edges: parameter ranges, placement without spray, stones or mist. */
class WaterPartsTest {

    @TempDir
    Path dir;

    @Test
    void parametersKeepToTheirRanges() {
        FlowingWaterParameters f = FlowingWaterParameters.DEFAULTS;
        for (float bad : new float[] {-0.5f, 1.5f}) {
            for (Supplier<Object> make : java.util.List.<Supplier<Object>>of(() -> f.withTurbidity(bad),
                () -> f.withRoughness(bad), () -> f.withFoamRoughness(bad), () -> f.withShallowClear(bad))) {
                assertThatThrownBy(make::get).isInstanceOf(IllegalArgumentException.class);
            }
        }
        FallingWaterParameters w = FallingWaterParameters.DEFAULTS;
        for (float bad : new float[] {-0.5f, 5f}) {
            for (Supplier<Object> make : java.util.List.<Supplier<Object>>of(() -> w.withOpacity(bad),
                () -> w.withRoughness(bad), () -> w.withEdge(bad), () -> w.withAtomising(bad))) {
                assertThatThrownBy(make::get).isInstanceOf(IllegalArgumentException.class);
            }
        }
    }

    @Test
    void placementLeavesOutWhatIsMissing() {
        WaterNetwork network = WaterNetwork.build(WaterSceneTest.CLIFF, WaterSceneTest.REGION,
            WaterParameters.DEFAULTS);
        try (var tasks = new com.truesim.common.jobs.TaskScheduler(); AssetCache cache = new AssetCache(tasks)) {
            SceneGraph scene = new SceneGraph(new ChangeClock());
            PathAssets paths = new PathAssets(cache);
            EngineSettings settings = new EngineSettings();
            settings.set(WaterRenderSettings.STONES, false);
            settings.set(WaterRenderSettings.MIST_DENSITY, 0f);
            WaterScene water = new WaterScene(scene, new WaterAssets(paths), network, WaterScene.Look.of(
                WaterMaterials.river(FlowingWaterParameters.DEFAULTS).ref(),
                WaterMaterials.sheet(FallingWaterParameters.DEFAULTS).ref(),
                WaterMaterials.jets(FallingWaterParameters.DEFAULTS).ref(),
                WaterMaterials.river(FlowingWaterParameters.DEFAULTS).ref()))
                .stones(new ProceduralAssets(paths, new GenerationCache(dir))).place(settings);
            var renderables = scene.components().store(Renderable.TYPE);
            for (long node : water.nodes()) {
                assertThat(scene.components().get(node, FogVolume.TYPE)).isNull();
                assertThat(renderables.get(node)).isNotNull();
            }
            assertThat(water.sprays()).isEmpty();
            water.close();
            // stones with their material
            settings.set(WaterRenderSettings.STONES, true);
            settings.set(WaterRenderSettings.STONE_LIMIT, 3);
            WaterScene stony = new WaterScene(scene, new WaterAssets(paths), network, WaterScene.Look.of(
                WaterMaterials.river(FlowingWaterParameters.DEFAULTS).ref(),
                WaterMaterials.sheet(FallingWaterParameters.DEFAULTS).ref(),
                WaterMaterials.jets(FallingWaterParameters.DEFAULTS).ref(),
                WaterMaterials.river(FlowingWaterParameters.DEFAULTS).ref()))
                .stones(new ProceduralAssets(paths, new GenerationCache(dir))).place(settings);
            assertThat(stony.nodes()).hasSize(network.chunks().size()
                + (int) network.falls().stream().filter(f -> f.free()).count() + 3);
            stony.close();
        }
    }

    @Test
    void theWetnessMapCoversBanksAndFalls() {
        WaterNetwork network = WaterNetwork.build(WaterSceneTest.CLIFF, WaterSceneTest.REGION,
            WaterParameters.DEFAULTS);
        WaterWetness wet = WaterWetness.of(network, new EngineSettings());
        assertThat(wet.tiles()).isPositive();
        var c = network.channels().getLast();
        int i = c.count() / 2;
        double rx = -c.directionZ(i);
        double rz = c.directionX(i);
        double edge = c.width(i) / 2 + 0.5;
        double surface = c.surface(i);
        // the bank just above the waterline is wet, a metre higher dry, far off dry
        assertThat(wet.wet(c.x(i) + rx * edge, surface + 0.2, c.z(i) + rz * edge)).isTrue();
        assertThat(wet.wet(c.x(i) + rx * edge, surface + 1.5, c.z(i) + rz * edge)).isFalse();
        assertThat(wet.wet(c.x(i) + 300, surface, c.z(i))).isFalse();
        assertThat(wet.wet(-5000, 0, -5000)).isFalse();
        // the rock behind the free fall, half way down
        var fall = network.tallest();
        var path = fall.path();
        int k = path.count() / 2;
        assertThat(wet.wet(path.x()[k], path.y()[k], path.z()[k])).isTrue();
        // laid out as flow.slang reads it
        var bytes = wet.bytes();
        var f32 = java.lang.foreign.ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(java.nio.ByteOrder.LITTLE_ENDIAN);
        var u32 = java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED.withOrder(java.nio.ByteOrder.LITTLE_ENDIAN);
        assertThat(bytes.get(f32, 0)).isEqualTo((float) WaterSceneTest.REGION.x0());
        assertThat(bytes.get(u32, 24)).isEqualTo(16);
        assertThat(bytes.get(u32, 28)).isEqualTo(WaterWetness.HEADER);
        assertThatThrownBy(() -> WaterWetness.of(network, -1, 1, 16)).isInstanceOf(IllegalArgumentException.class);
        // the GPU's lookup (flow.slang's wetness) over the bytes agrees with the map
        double bx = c.x(i) + rx * edge;
        double bz = c.z(i) + rz * edge;
        assertThat(shaderWet(bytes, bx, surface + 0.2, bz)).isPositive();
        assertThat(shaderWet(bytes, bx, surface + 1.5, bz)).isZero();
        assertThat(shaderWet(bytes, path.x()[k], path.y()[k], path.z()[k])).isPositive();
    }

    /** flow.slang's wetness(), in Java over the map's bytes (fade 0.35). */
    static double shaderWet(java.lang.foreign.MemorySegment m, double x, double y, double z) {
        var f32 = java.lang.foreign.ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(java.nio.ByteOrder.LITTLE_ENDIAN);
        var u32 = java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED.withOrder(java.nio.ByteOrder.LITTLE_ENDIAN);
        float x0 = m.get(f32, 0);
        float z0 = m.get(f32, 4);
        float tile = m.get(f32, 12);
        int tilesX = m.get(u32, 16);
        int tilesZ = m.get(u32, 20);
        int cells = m.get(u32, 24);
        float tx = (float) ((x - x0) / tile);
        float tz = (float) ((z - z0) / tile);
        if (tx < 0 || tz < 0 || tx >= tilesX || tz >= tilesZ) {
            return 0;
        }
        int ti = Math.min((int) tx, tilesX - 1);
        int tj = Math.min((int) tz, tilesZ - 1);
        int number = m.get(u32, m.get(u32, 28) + 4L * (tj * tilesX + ti));
        if (number == 0) {
            return 0;
        }
        int ci = Math.min((int) ((tx - ti) * cells), cells - 1);
        int cj = Math.min((int) ((tz - tj) * cells), cells - 1);
        int word = m.get(u32, m.get(u32, 36) + 4L * ((long) (number - 1) * cells * cells + cj * cells + ci));
        if (word == 0) {
            return 0;
        }
        float base = m.get(f32, m.get(u32, 32) + 4L * (number - 1));
        float top = base + Float.float16ToFloat((short) (word & 0xffff));
        float bottom = base + Float.float16ToFloat((short) (word >>> 16));
        if (y < bottom) {
            return 0;
        }
        double t = Math.clamp((y - (top - 0.35)) / 0.35, 0, 1);
        return 1 - t * t * (3 - 2 * t);
    }
}
