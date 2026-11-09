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

[MaterialParameters(0)]
public struct FallingWaterParameters
{
    [Color] public float3 color = float3(0.90, 0.93, 0.95);
    [Color] public float3 glassy = float3(0.55, 0.68, 0.70); // the coherent water at the lip, before it aerates
    [Range(0, 1)] public float opacity = 0.9;    // the sheet's coverage where it is whole
    [Range(0, 1)] public float roughness = 0.45;
    [Range(0, 1)] public float edge = 0.35;      // how far in from the sides it thins (a share of the half width)
    [Range(0, 4)] public float atomising = 1.6;  // how fast coverage falls as the water atomises
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
        float coverage = p.opacity * sides * whole;
        if (p.jets != 0)
        {
            coverage *= 0.8;
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
};
