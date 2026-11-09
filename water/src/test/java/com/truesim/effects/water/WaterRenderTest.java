package com.truesim.effects.water;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.truesim.common.procedural.assets.WaterAssets;
import com.truesim.common.procedural.water.WaterNetwork;
import com.truesim.common.procedural.water.WaterParameters;
import com.truesim.effects.water.materials.FallingWaterParameters;
import com.truesim.effects.water.materials.FlowingWaterParameters;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.vulkan.testing.TestGpu;
import com.truesim.scene.assets.PathAssets;
import org.junit.jupiter.api.Test;

/**
 * The water drawn by the engine (the visibility buffer and sorted transparency, the flow hook, the spray's particles
 * and the mist's fog): a stream over a cliff seen from below the fall. The water shows (the image differs from the
 * scene without it), the flow moves it (frames apart in time differ where the water is, and only there), and with the
 * hook off it stands still; validated, no errors.
 */
class WaterRenderTest {

    private static final int W = 160;
    private static final int H = 96;

    @Test
    void theWaterShowsAndFlows() throws Exception {
        WaterNetwork network = WaterNetwork.build(WaterSceneTest.CLIFF, WaterSceneTest.REGION,
            WaterParameters.DEFAULTS);
        EngineSettings settings = new EngineSettings();
        settings.set(WaterRenderSettings.STONES, false);
        try (TestGpu gpu = TestGpu.open(); WaterRig rig = new WaterRig(gpu, W, H, settings, 160, 40)) {
            rig.fovY = Math.toRadians(60);
            rig.look(60, 1845, 1160, -60, -10, -150);
            rig.settleScene(5);
            float[] empty = rig.rgb();
            WaterScene water = new WaterScene(rig.scene, new WaterAssets(new PathAssets(rig.assets)), network,
                WaterScene.Look.of(WaterMaterials.river(FlowingWaterParameters.DEFAULTS).ref(),
                    WaterMaterials.sheet(FallingWaterParameters.DEFAULTS).ref(),
                    WaterMaterials.jets(FallingWaterParameters.DEFAULTS).ref(), null))
                .effects(rig.effects).place(settings);
            rig.frameSeconds = 0;
            rig.settleScene(10);
            rig.flow.time(10);
            rig.frame();
            float[] a = rig.rgb();
            assertThat(changed(empty, a)).as("pixels the water changes").isGreaterThan(W * H / 100);
            rig.flow.time(10.5);
            rig.frame();
            float[] b = rig.rgb();
            assertThat(changed(a, b)).as("pixels the flow moves").isPositive();
            rig.flow.active(false);
            rig.frame();
            float[] c = rig.rgb();
            rig.flow.time(20);
            rig.frame();
            float[] d = rig.rgb();
            assertThat(changed(c, d)).as("still without the hook, but for the spray").isLessThan(changed(a, b) + 1);
            water.close();
            // the flow's clock
            WaterFlow flow = rig.flow;
            assertThat(flow.id()).isEqualTo(WaterFlow.ID);
            assertThat(flow.provides()).isEmpty();
            assertThat(flow.view()).isSameAs(rig.view);
            flow.time(-1);
            assertThat(flow.time()).isEqualTo(86_399);
            flow.advance(2);
            assertThat(flow.time()).isEqualTo(1);
            assertThatThrownBy(() -> flow.advance(-1)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    /** Pixels differing by more than 1 %. */
    private static int changed(float[] a, float[] b) {
        int n = 0;
        for (int i = 0; i < a.length / 3; i++) {
            double d = 0;
            double m = 1e-4;
            for (int k = 0; k < 3; k++) {
                d += Math.abs(a[3 * i + k] - b[3 * i + k]);
                m += Math.abs(a[3 * i + k]);
            }
            if (d / m > 0.01) {
                n++;
            }
        }
        return n;
    }
}
