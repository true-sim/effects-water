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
}
