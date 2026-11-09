// The test sources' Slang. Its particle module makes the build generate the tests' particle kernel dispatch with the
// libraries' modules (effects-particles' built-ins), which the tests' shading listing (TestWaterShadingHooks) needs to
// rebuild the particles' simulation with every hook: a project with no particle module of its own gets none.
module testWater;

import particles.particle;

/** Quadratic drag: falling spray slowed toward a terminal speed (a test module: the films' spray uses the built-ins). */
[ParticleModule("water.test.quadraticDrag")]
public struct QuadraticDrag
{
    public float terminal = 9;

    public void force(inout ParticleStep s, Particle p)
    {
        s.k += 9.81 * length(p.velocity) / max(terminal * terminal, 1e-3);
    }
};
