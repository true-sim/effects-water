// Flowing water: the surface of rivers and streams (common-procedural-water's channel meshes), transmissive, showing
// the bed through it refracted and absorbed over its depth, with the milky scatter of glacial silt, Fresnel
// reflections of the environment and foam. Its meshes carry the flow per vertex (common-procedural-water's
// WaterVertex):
//
//   TANGENT   the direction of flow, a unit vector
//   UV0       x the speed, metres a second; y across, -1 at the left bank to 1 at the right
//   UV1       x the depth there, metres
//   COLOR     r foam (Froude number, emergent stones, plunge pools); g the shore, 0 mid-channel to 1 at the edge
//
// What moves (flow-mapped ripples, foam drifting with the current, the shore lapping) is the flow hook's
// (flow.slang): this type stashes the flow for it (waterMark.slang).
module flowingWater;

import materials;
import water.waterMark;

[MaterialParameters(FEATURE_TRANSMISSION | FEATURE_VOLUME | FEATURE_IOR)]
public struct FlowingWaterParameters
{
    [Color] public float3 attenuationColor = float3(0.66, 0.84, 0.80); // white light after attenuationDistance
    public float attenuationDistance = 1.4;     // metres: glacial water is clouded by its silt
    [Color] public float3 silt = float3(0.50, 0.62, 0.60); // the light the silt scatters back
    [Range(0, 1)] public float turbidity = 0.18; // how much is scattered back rather than passing through
    [Range(0, 1)] public float roughness = 0.05;
    public float ior = 1.33;
    [Color] public float3 foamColor = float3(0.88, 0.91, 0.93);
    [Range(0, 1)] public float foamRoughness = 0.6;
    [Range(0, 1)] public float shallowClear = 0.8; // how clear the water turns where it is shallow (the shore)
};

[MaterialType("flowingWater")]
public struct FlowingWater : IMaterial
{
    public typealias Parameters = FlowingWaterParameters;

    public static Surface surface(FlowingWaterParameters p, MaterialRef m, SurfaceInput in)
    {
        float3 n = normalize(in.normal);
        Surface s = Surface.defaults(n);
        float speed = max(in.uv.x, 0.0);
        float depth = max(in.uv1.x, 0.0);
        float foam = saturate(in.color.r);
        float shore = saturate(in.color.g);
        float3 direction = dot(in.tangent.xyz, in.tangent.xyz) > 1e-6 ? normalize(in.tangent.xyz) : float3(1, 0, 0);
        float r0 = (p.ior - 1) / (p.ior + 1);
        s.metallic = 0;
        s.ior = p.ior;
        s.f0 = r0 * r0;
        s.roughness = p.roughness;
        // the silt scatters some of the light back (milky), the rest passes through, absorbed over the depth
        float turbid = p.turbidity * (1 - p.shallowClear * shore);
        s.albedo = p.silt;
        s.transmission = 1 - turbid;
        s.thickness = max(depth, 0.01);
        s.attenuationColor = p.attenuationColor;
        s.attenuationDistance = p.attenuationDistance;
        // the vertex's foam, before the hook breaks it into drifting patches
        float f = foam * 0.35;
        s.albedo = lerp(s.albedo, p.foamColor, f);
        s.transmission *= 1 - f;
        s.roughness = lerp(s.roughness, p.foamRoughness, f);
        WaterFlow flow;
        flow.kind = WATER_CHANNEL;
        flow.direction = direction;
        flow.speed = speed;
        flow.foam = foam;
        flow.side = shore;
        flow.extra = depth;
        stashFlow(s, flow);
        return s;
    }
};
