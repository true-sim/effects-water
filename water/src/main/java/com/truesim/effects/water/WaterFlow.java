package com.truesim.effects.water;

import com.truesim.annotations.AllowAllocation;
import com.truesim.annotations.AllowImpure;
import com.truesim.common.graph.Id;
import com.truesim.effects.water.materials.WaterFlowParameters;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.PassContext;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.render.Keys;
import com.truesim.engine.render.SceneView;
import com.truesim.engine.render.ShadingHookParameters;
import com.truesim.engine.render.ShadingHookSource;
import com.truesim.engine.render.ShadingHooks;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Objects;

/**
 * The water's flow in a view: adds the {@value #HOOK} surface hook (water/flow.slang) to the view's shading with the
 * scene's values for it (engine-scene's {@code ShadingHookComponent}, or the record's defaults) and this technique's
 * clock, so ripples drift with the current, foam travels and falls stream down. Without it the water types' surfaces
 * light as they are, still.
 *
 * <pre>{@code
 * WaterFlow flow = new WaterFlow(view);
 * port.add(flow);
 * // each frame, before it records:
 * flow.advance(frame.delta());
 * }</pre>
 */
public final class WaterFlow implements Technique {

    /** The hook's name: its {@code [ShadingHook]}. */
    public static final String HOOK = "water.flow";
    /** The technique's id. */
    public static final Id<WaterFlow> ID = Id.of("truesim.water.flow", WaterFlow.class);

    private static final ValueLayout.OfFloat F32 = ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    /** A day: the clock wraps so float seconds keep their precision. */
    private static final double WRAP = 86_400;

    private final SceneView view;
    private volatile double time;
    private volatile boolean active = true;

    /** The flow in {@code view}. */
    public WaterFlow(SceneView view) {
        this.view = Objects.requireNonNull(view, "view");
    }

    @Override
    public Id<WaterFlow> id() {
        return ID;
    }

    /** The view it is in. */
    public SceneView view() {
        return view;
    }

    @Override
    public List<Key<?>> provides() {
        return List.of();
    }

    /** It adds its hook to the view's: built before the view's shading. */
    @Override
    public List<Key<?>> modifies() {
        return List.of(Keys.SHADING_HOOKS);
    }

    /** Moves the clock on by {@code seconds} (0: the water stands still, as a paused world's). */
    @AllowImpure("keeps the flow's clock")
    public WaterFlow advance(double seconds) {
        if (!(seconds >= 0)) {
            throw new IllegalArgumentException("Time runs forwards: " + seconds);
        }
        time = (time + seconds) % WRAP;
        return this;
    }

    /** Sets the clock (a film's frame, a test). */
    @AllowImpure("keeps the flow's clock")
    public WaterFlow time(double seconds) {
        time = ((seconds % WRAP) + WRAP) % WRAP;
        return this;
    }

    /** The clock, seconds. */
    public double time() {
        return time;
    }

    /** Turns the flow on or off from the next frame (its bit of the view's hook block: no rebuild). */
    @AllowImpure("a switch")
    public WaterFlow active(boolean on) {
        active = on;
        return this;
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public void build(GraphBuilder g) {
        ShadingHooks.of(view.view().renderer().device()).add(g, HOOK, new ShadingHookSource() {
            @Override
            public int bytes() {
                return WaterFlowParameters.BYTES;
            }

            @Override
            public boolean active() {
                return active;
            }

            @Override
            public void write(PassContext ctx, ShadingHookParameters authored, MemorySegment into, long offset) {
                authored.write(into, offset);
                into.set(F32, offset + WaterFlowParameters.TIME.offset(), (float) time);
            }
        });
    }
}
