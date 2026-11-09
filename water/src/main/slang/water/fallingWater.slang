// A waterfall's falling water: the sheet leaving the lip and the jets it breaks into (common-procedural-water's fall
// meshes: submesh 0 the sheet, 1 the jets), alpha-blended: dense and glassy-white at the lip, aerated below, thinning
// as it atomises into spray (the particles' and the mist's). Its meshes carry, per vertex (WaterVertex):
//
//   TANGENT   down the fall, a unit vector
//   UV0       x the speed; y across the sheet, -1 to 1
//   UV1       x the sheet's thickness, metres; y the metres fallen over the break-up length (1: where it breaks up)
//   COLOR     r foam (aeration); g the sheet's edges; b the fraction already atomised
//
// The streaks running down it are the flow hook's (flow.slang), which this type hands its flow (waterMark.slang).
module fallingWater;

import materials;
import water.waterMark;
__exported import water.sway;

/** The sway hook's index in the shading hooks' dispatch, resolved when a shader links. */
extern static const uint RUN_HOOK_WATER_SWAY_INDEX;

[MaterialParameters(GEOMETRY_DISPLACE)]
public struct FallingWaterParameters
{
    [Color] public float3 color = float3(0.90, 0.93, 0.95);
    [Color] public float3 glassy = float3(0.55, 0.68, 0.70); // the coherent water at the lip, before it aerates
    [Range(0, 1)] public float opacity = 0.9;    // the sheet's coverage where it is whole
    [Range(0, 1)] public float roughness = 0.45;
    [Range(0, 1)] public float edge = 0.35;      // how far in from the sides it thins (a share of the half width)
    [Range(0, 4)] public float atomising = 1.2;  // how fast the water's own coverage falls as it atomises
    [Range(0, 1)] public float veil = 0.45;      // the coverage left once atomised: the spray's white veil
    public int jets = 0;                         // 1: the jets' submesh (thinner, ragged)
};

[MaterialType("waterfall")]
public struct FallingWater : IMaterial
{
    public typealias Parameters = FallingWaterParameters;

    public static Surface surface(FallingWaterParameters p, MaterialRef m, SurfaceInput in)
    {
        float3 n = normalize(in.normal);
        if (dot(n, in.toViewer) < 0)
        {
            n = -n; // both faces of a sheet show alike
        }
        Surface s = Surface.defaults(n);
        float speed = max(in.uv.x, 0.0);
        float across = clamp(in.uv.y, -1.0, 1.0);
        float broken = max(in.uv1.y, 0.0);
        float aeration = saturate(in.color.r);
        float atomised = saturate(in.color.b);
        float3 down = dot(in.tangent.xyz, in.tangent.xyz) > 1e-6 ? normalize(in.tangent.xyz) : float3(0, -1, 0);
        float sides = 1 - smoothstep(1 - p.edge, 1.0, abs(across));
        float whole = pow(saturate(1 - atomised), p.atomising);
        float coverage = p.opacity * sides * lerp(p.veil, 1.0, whole);
        if (p.jets != 0)
        {
            coverage *= 0.3; // ragged streaks over the veil, not a second sheet
        }
        s.alpha = saturate(coverage);
        s.albedo = lerp(p.glassy, p.color, saturate(max(aeration, broken * 0.5)));
        s.roughness = p.roughness;
        s.metallic = 0;
        WaterFlow flow;
        flow.kind = p.jets != 0 ? WATER_JET : WATER_SHEET;
        flow.direction = down;
        flow.speed = speed;
        flow.foam = aeration;
        flow.side = across;
        flow.extra = atomised;
        stashFlow(s, flow);
        return s;
    }

    /**
     * The veil swaying in the wind (water.sway): the mesh hangs from its lip (y 0 there, -fallen below), so a vertex
     * drifts downwind by the wind's drift times how far it has fallen, the drift swinging slowly and travelling down
     * the fall, and a little across it.
     */
    public override static Displaced displace(FallingWaterParameters p, MaterialRef m, DisplacePoint in)
    {
        Displaced d = Displaced.of(in);
        if (!in.hookOn(RUN_HOOK_WATER_SWAY_INDEX))
        {
            return d;
        }
        WaterSwayParameters* wind = in.hook<WaterSwayParameters>(RUN_HOOK_WATER_SWAY_INDEX);
        float speed = wind[0].speed;
        if (!(speed > 0))
        {
            return d;
        }
        float fallen = max(-in.position.y, 0.0);
        float time = wind[0].time;
        float2 level = wind[0].direction.xz;
        level = dot(level, level) > 1e-12 ? normalize(level) : float2(1, 0);
        float period = max(wind[0].period, 0.5);
        // the swing travels down the fall at about the falling water's terminal speed
        float phase = 6.2831853 * (time / period - fallen / (9.0 * period));
        float swing = 1 + wind[0].sway * sin(phase) + 0.5 * wind[0].sway * sin(2.3 * phase + 1.7);
        float drift = min(wind[0].drift * speed, 1.0) * fallen * swing;
        float3 along = in.toMesh(float3(level.x, 0, level.y));
        float3 across = in.toMesh(float3(-level.y, 0, level.x));
        d.position = in.position + along * drift + across * (0.15 * drift * sin(phase * 0.7 + 0.4));
        return d;
    }
};
