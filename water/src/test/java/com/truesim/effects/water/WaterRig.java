package com.truesim.effects.water;

import static org.assertj.core.api.Assertions.assertThat;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;

import com.truesim.common.jobs.TaskScheduler;
import com.truesim.common.state.ChangeClock;
import com.truesim.effects.atmosphere.fog.AtmosphereFog;
import com.truesim.effects.atmosphere.fog.FogRegions;
import com.truesim.effects.atmosphere.render.AerialPerspective;
import com.truesim.effects.atmosphere.render.Atmosphere;
import com.truesim.effects.atmosphere.render.AtmosphereSky;
import com.truesim.effects.atmosphere.render.SkyCapture;
import com.truesim.effects.particles.ParticleDraw;
import com.truesim.effects.particles.ParticleSimulation;
import com.truesim.effects.particles.Particles;
import com.truesim.effects.particles.scene.EffectSync;
import com.truesim.effects.terrain.Terrain;
import com.truesim.effects.terrain.data.TerrainModifiers;
import com.truesim.effects.terrain.data.TerrainSite;
import com.truesim.effects.terrain.material.TerrainType;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.Anchor;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Order;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.graph.testing.TestTarget;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.ChosenTransparency;
import com.truesim.engine.render.ClusteredLights;
import com.truesim.engine.render.Environment;
import com.truesim.engine.render.EnvironmentLighting;
import com.truesim.engine.render.GeometryPool;
import com.truesim.engine.render.GpuScene;
import com.truesim.engine.render.Keys;
import com.truesim.engine.render.Lights;
import com.truesim.engine.render.Materials;
import com.truesim.engine.render.MeshletRenderer;
import com.truesim.engine.render.SceneGeometry;
import com.truesim.engine.render.SceneView;
import com.truesim.engine.render.ShadingHookValues;
import com.truesim.engine.render.Textures;
import com.truesim.engine.render.ViewOptions;
import com.truesim.engine.render.VisibilityBufferShading;
import com.truesim.engine.render.shadows.Shadows;
import com.truesim.engine.scene.SceneResources;
import com.truesim.engine.scene.SceneSync;
import com.truesim.engine.scene.ShadingHookComponent;
import com.truesim.engine.scene.ShadingHookSync;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.testing.TestGpu;
import com.truesim.scene.assets.AssetCache;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.core.ShadowSpec;
import com.truesim.scene.effects.Effects;
import com.truesim.scene.render.MaterialData;
import com.truesim.scene.world.World;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.vulkan.VkBufferImageCopy;

/**
 * The whole path a game takes, for the films: an asset cache on a task scheduler, the scene resources, a scene with
 * its world streaming and the terrain in it, the water placed in the scene, the sync onto a GPU scene, a meshlet
 * renderer drawing it through a frame graph (the visibility buffer, sorted transparency for the water) under the
 * atmosphere's sky and sun with cascaded shadows, aerial perspective, the fog's media (the mist's volumes), the
 * particles (the spray) and the water's flow hook; half-float colour read back every frame.
 */
final class WaterRig implements AutoCloseable {

    final TestGpu gpu;
    final VulkanDevice device;
    final int width;
    final int height;
    final FrameSlots slots = new FrameSlots(2);
    final GeometryPool pool;
    final Materials materials;
    final Textures textures;
    final TaskScheduler tasks = new TaskScheduler();
    final AssetCache assets;
    final SceneResources resources;
    final SceneGraph scene = new SceneGraph(new ChangeClock());
    final World world;
    final GpuScene gpuScene;
    final SceneSync sync;
    final ShadingHookSync hooks;
    final MeshletRenderer renderer;
    final SceneGeometry geometry;
    final Lights lights;
    final FrameGraph graph;
    final EngineSettings settings;
    final Camera camera = new Camera();
    final SceneView view;
    final ViewPort port;
    final Atmosphere atmosphere;
    final Particles particles;
    final Effects effects;
    final EffectSync effectSync;
    final FogRegions fog;
    final WaterFlow flow;
    final WaterSway sway;
    private final GpuBuffer[] readbacks;
    private final TestTarget target;
    private final List<AutoCloseable> closing = new ArrayList<>();
    Terrain terrain;
    double fovY = Math.toRadians(55);
    double frameSeconds = 1 / 30.0;
    private int lastSlot;

    /** A rig of {@code width} x {@code height}, the sun {@code elevation} degrees up at {@code azimuth} from north. */
    WaterRig(TestGpu gpu, int width, int height, EngineSettings settings, double azimuth, double elevation) {
        this.gpu = gpu;
        this.device = gpu.device();
        this.width = width;
        this.height = height;
        this.settings = settings;
        int color = VK_FORMAT_R16G16B16A16_SFLOAT;
        pool = new GeometryPool(device, GeometryPool.Capacity.of(1 << 21, 1 << 15, 512), slots);
        materials = new Materials(device, 256, slots);
        materials.register(TerrainType.TYPE);
        WaterMaterials.register(materials);
        textures = new Textures(device, slots);
        assets = new AssetCache(tasks);
        resources = new SceneResources(assets, pool, materials, textures);
        world = new World(scene, tasks);
        gpuScene = new GpuScene(device, pool, 1 << 16, slots);
        sync = new SceneSync(resources, scene, gpuScene);
        hooks = new ShadingHookSync(scene, ShadingHookValues.of(device));
        renderer = new MeshletRenderer(device, gpuScene, color, VK_FORMAT_D32_SFLOAT, slots);
        geometry = SceneGeometry.meshlets(renderer);
        lights = new Lights(device, 4, 8, slots);
        graph = new FrameGraph(device, slots);
        graph.settings(settings);
        port = graph.addView("main").size(width, height);
        view = geometry.createView(port, camera, ViewOptions.of(1 << 17, 1 << 23)).set(1, MeshletRenderer.MODE_LIT);
        port.add(new VisibilityBufferShading(geometry, view, color, VK_FORMAT_D32_SFLOAT))
            .add(new ClusteredLights(lights, view)).add(new ChosenTransparency(geometry, view))
            .add(new com.truesim.engine.render.ScreenSpaceReflections(view));
        // the water reflects the cliffs, stones and banks, not only the sky (engine-render's render.ssr.transparent)
        settings.set(com.truesim.engine.render.ScreenSpaceReflections.TRANSPARENT, true);
        atmosphere = closing(new Atmosphere(device, slots, graph.settings()));
        Environment environment = Environment.uniform("atmosphere", 0, 0, 0);
        SkyCapture capture = closing(new SkyCapture(atmosphere, environment, textures));
        lights.directional(1, 0, -1, 0, 0, 0, 0);
        lights.setCount(2);
        lights.shadow(0, ShadowSpec.cascades(4, 2048, 20_000));
        Shadows shadows = closing(new Shadows(geometry, lights));
        double az = Math.toRadians(azimuth);
        double el = Math.toRadians(elevation);
        // towards the sun: +x east, +y up, -z north
        atmosphere.fixedSun(Math.sin(az) * Math.cos(el), Math.sin(el), -Math.cos(az) * Math.cos(el), 100_000);
        fog = new FogRegions(scene);
        // the air itself clear: only the mist's volumes
        long air = scene.create();
        scene.components().set(air, ShadingHookComponent.TYPE, new ShadingHookComponent(AtmosphereFog.HOOK,
            MaterialData.builder(AtmosphereFog.HOOK).set("density", 0f).build().values(), true));
        flow = new WaterFlow(view);
        sway = new WaterSway(gpuScene);
        particles = closing(new Particles(device, slots, 96L << 20, 4096));
        particles.assets(resources);
        effects = new Effects(scene);
        effectSync = new EffectSync(effects, particles);
        port.add(new EnvironmentLighting(environment, view)).add(capture).add(shadows.view(view))
            .add(new AtmosphereSky(atmosphere, view)).add(new AerialPerspective(atmosphere, view))
            .add(new AtmosphereFog(view, fog).lighting(atmosphere)).add(flow).add(new ParticleDraw(particles, view));
        graph.add(new ParticleSimulation(particles));
        readbacks = new GpuBuffer[] {GpuBuffer.hostVisible(device, 8L * width * height,
            VK_BUFFER_USAGE_TRANSFER_DST_BIT), GpuBuffer.hostVisible(device, 8L * width * height,
            VK_BUFFER_USAGE_TRANSFER_DST_BIT)};
        target = new TestTarget(device, width, height, com.truesim.engine.vulkan.Format.R16G16B16A16_SFLOAT);
        port.add(new Readback(this)).output(Keys.SCENE_COLOR, target.output());
        camera.position(0, 2000, 0).set(0, -1, 0, 0, 0, -1, fovY, width, height, 0.5);
    }

    /** The terrain of {@code site} changed by {@code modifiers}, with {@code material}. */
    Terrain terrain(TerrainSite site, AssetRef<MaterialData> material, TerrainModifiers modifiers) {
        terrain = new Terrain(world, resources, site, material, settings, modifiers);
        return terrain;
    }

    <T extends AutoCloseable> T closing(T closeable) {
        closing.add(closeable);
        return closeable;
    }

    /** Points the camera from ({@code x}, {@code y}, {@code z}) along ({@code dx}, {@code dy}, {@code dz}). */
    WaterRig look(double x, double y, double z, double dx, double dy, double dz) {
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double fx = dx / length;
        double fy = dy / length;
        double fz = dz / length;
        double ux = -fy * fx;
        double uy = 1 - fy * fy;
        double uz = -fy * fz;
        double ul = Math.sqrt(ux * ux + uy * uy + uz * uz);
        camera.position(x, y, z).set(fx, fy, fz, ux / ul, uy / ul, uz / ul, fovY, width, height, 0.1);
        return this;
    }

    /** One frame: loads arrive, the terrain follows the camera, the scene and its effects sync, the graph draws. */
    void frame() {
        int slot = graph.beginFrame();
        slots.begin(slot);
        tasks.drainCompleted();
        resources.beginFrame();
        if (terrain != null) {
            terrain.follow(camera.position(0), camera.position(1), camera.position(2), fovY, height);
        }
        world.update();
        if (terrain != null) {
            terrain.update(frameSeconds);
        }
        atmosphere.update(null, camera.position(0), camera.position(1), camera.position(2));
        atmosphere.light(lights, 0, 1);
        effects.advance(frameSeconds);
        flow.advance(frameSeconds);
        sway.update(frameSeconds, settings);
        scene.update();
        sync.update();
        hooks.update();
        fog.update();
        effectSync.clearViewers();
        effectSync.addViewer(camera.position(0), camera.position(1), camera.position(2));
        effectSync.update(frameSeconds);
        view.set(1, MeshletRenderer.MODE_LIT);
        graph.submit(0, 0);
        slots.end();
        lastSlot = slot;
    }

    /** Frames until the terrain shows what the world wants here and nothing loads (at most two minutes). */
    void settle(int minFrames) throws InterruptedException {
        long deadline = System.nanoTime() + 180_000_000_000L;
        for (int f = 0; System.nanoTime() < deadline; f++) {
            double keep = frameSeconds;
            frameSeconds = 0;
            frame();
            frameSeconds = keep;
            if (f >= minFrames && terrain.source() != null && world.isReady(terrain.source())
                && assets.loadingCount() == 0 && resources.loaded() && terrain.pendingTiles() == 0
                && world.loadingCount() == 0 && terrain.transitioning() == 0) {
                device.waitIdle();
                return;
            }
            Thread.sleep(1);
        }
        device.waitIdle();
    }

    /**
     * Loads {@code materials} before anything draws with them: a material instances took keeps displacing or not
     * (engine-render's Materials.set), so the falls' displacing materials must arrive before their nodes do.
     */
    @SafeVarargs
    final void preload(AssetRef<MaterialData>... materials) throws InterruptedException {
        for (AssetRef<MaterialData> m : materials) {
            assets.acquire(m);
        }
        long deadline = System.nanoTime() + 60_000_000_000L;
        for (AssetRef<MaterialData> m : materials) {
            while (assets.get(m) == null) {
                assertThat(System.nanoTime()).as("the materials loading").isLessThan(deadline);
                tasks.drainCompleted();
                Thread.sleep(1);
            }
        }
    }

    /** Frames until nothing loads (without a terrain), at least {@code minFrames}. */
    void settleScene(int minFrames) throws InterruptedException {
        long deadline = System.nanoTime() + 300_000_000_000L;
        for (int f = 0; System.nanoTime() < deadline; f++) {
            frame();
            if (f >= minFrames && assets.loadingCount() == 0 && resources.loaded()) {
                frame();
                device.waitIdle();
                return;
            }
            Thread.sleep(2);
        }
        throw new AssertionError("The scene did not load in five minutes: " + assets.loadingCount() + " loading");
    }

    /** The last frame's colour, rgb per pixel, in scene units. */
    float[] rgb() {
        device.waitIdle();
        MemorySegment memory = readbacks[lastSlot].memory();
        float[] out = new float[3 * width * height];
        for (int i = 0; i < width * height; i++) {
            for (int c = 0; c < 3; c++) {
                out[3 * i + c] = Float.float16ToFloat(memory.get(ValueLayout.JAVA_SHORT_UNALIGNED, 8L * i + 2L * c));
            }
        }
        return out;
    }

    @Override
    public void close() {
        device.waitIdle();
        for (int i = closing.size() - 1; i >= 0; i--) {
            try {
                closing.get(i).close();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        if (terrain != null) {
            terrain.close();
            scene.update();
            sync.update();
        }
        graph.close();
        target.close();
        for (GpuBuffer b : readbacks) {
            b.close();
        }
        renderer.close();
        sync.close();
        world.close();
        resources.close();
        gpuScene.close();
        lights.close();
        assets.close();
        tasks.close();
        textures.close();
        materials.close();
        pool.close();
        assertThat(gpu.validationErrors()).as("validation errors").isZero();
    }

    /** Copies the scene's colour into the frame slot's readback after everything drawn over it. */
    private record Readback(WaterRig rig) implements Technique {
        @Override
        public List<Key<?>> provides() {
            return List.of();
        }

        @Override
        public List<Key<?>> modifies() {
            return List.of(Keys.SCENE_COLOR);
        }

        @Override
        public Order order() {
            return Order.at(Anchor.POST);
        }

        @Override
        public void build(GraphBuilder g) {
            Image color = g.modify(Keys.SCENE_COLOR);
            g.pass("readback").read(color, Access.TRANSFER_SRC).sideEffects().record(ctx -> {
                try (var stack = stackPush()) {
                    VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
                    region.bufferRowLength(rig.width).imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .layerCount(1);
                    region.imageExtent().set(rig.width, rig.height, 1);
                    org.lwjgl.vulkan.VK10.vkCmdCopyImageToBuffer(ctx.unsafe().commands(), ctx.unsafe().image(color),
                        VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        rig.readbacks[rig.graph.frameSlots().current()].handle(), region);
                }
            });
            g.provide(Keys.SCENE_COLOR, color);
        }
    }
}
