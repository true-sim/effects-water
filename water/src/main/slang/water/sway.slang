// The wind the falls sway in: a geometry hook (engine-render's IGeometryHook: a record and its bit, no function) whose
// record the scene's geometry hook block carries for the waterfall type's displace() (fallingWater.slang). WaterSway
// (Java) writes it each frame with its clock; a speed of 0 (or the hook off) leaves the veils hanging straight.
module sway;

import hooks;

[ShadingHookParameters]
public struct WaterSwayParameters
{
    public float3 direction = float3(1, 0, 0); // where the wind blows, in the world (its level part counts)
    public float speed = 0;                    // metres a second at the falls
    public float drift = 0.18;                 // metres sideways per metre fallen, per (m/s) of wind, at most 1
    public float sway = 0.35;                  // how much the drift swings, as a share of it
    public float period = 7;                   // seconds a swing takes
    public float time = 0;                     // seconds: WaterSway's clock
};

[ShadingHook("water.sway")]
public struct WaterSway : IGeometryHook
{
    public typealias Parameters = WaterSwayParameters;
};
