package edu.barrier;

import org.apache.commons.math3.distribution.LogNormalDistribution;
import org.apache.commons.math3.random.JDKRandomGenerator;

import java.util.Random;

/**
 * Injects stochastic per-phase noise into MPI rank execution times,
 * modelling cloud VM performance variability:
 *   - multi-tenancy / noisy-neighbor interference
 *   - hypervisor scheduling jitter
 *   - network latency variation
 *
 * Noise models follow the literature:
 *   - Gaussian   : Hoefler et al. SC 2010 (symmetric HPC baseline)
 *   - Lognormal  : Underwood et al. ICPE 2018 (fits cloud latency distributions)
 *   - Bursty     : Ahuja et al. Information 2025 (multi-tenant spike events)
 */
public class NoiseInjector {

    /** Available cloud noise distribution families. */
    public enum NoiseModel {
        NONE,       // deterministic baseline — no variability
        GAUSSIAN,   // symmetric bell-curve: N(0, ε)
        LOGNORMAL,  // heavy right tail: zero-mean lognormal
        BURSTY      // 5 % spike events: noisy-neighbour model
    }

    private final Random rng;
    private final JDKRandomGenerator csmRng; // for Apache Commons Math

    public NoiseInjector(long seed) {
        this.rng    = new Random(seed);
        this.csmRng = new JDKRandomGenerator((int)(seed & 0xFFFFFFFFL));
    }

    /**
     * Sample a fractional perturbation η for one rank in one phase.
     * Actual rank time = baseTime * (1 + η).
     * Clamped so effective time ≥ 1 % of ideal.
     *
     * @param model   noise distribution
     * @param epsilon noise magnitude (e.g. 0.10 = 10 % scale)
     * @return η ∈ (−0.99, ∞)
     */
    public double sample(NoiseModel model, double epsilon) {
        return clamp(rawSample(model, epsilon));
    }

    private double rawSample(NoiseModel model, double epsilon) {
        switch (model) {
            case NONE:
                return 0.0;

            case GAUSSIAN:
                // Symmetric jitter: equal chance of faster or slower
                return epsilon * rng.nextGaussian();

            case LOGNORMAL: {
                // Zero-mean lognormal: heavy right tail, always ≥ −1
                // σ chosen so Var[η] = ε²
                double sigma = Math.sqrt(Math.log(1.0 + epsilon * epsilon));
                double mu    = -0.5 * sigma * sigma;
                // Use Apache Commons Math LogNormalDistribution for correctness
                LogNormalDistribution lnd =
                    new LogNormalDistribution(csmRng, mu, sigma);
                return lnd.sample() - 1.0;
            }

            case BURSTY: {
                // With 5 % probability: a large noisy-neighbour spike (≈ 5ε)
                // Otherwise: near-zero residual noise
                if (rng.nextDouble() < 0.05) {
                    return epsilon * 5.0 * (1.0 + 0.2 * rng.nextGaussian());
                } else {
                    return epsilon * 0.05 * rng.nextGaussian();
                }
            }

            default:
                return 0.0;
        }
    }

    /** Clamp so effective time never drops below 1 % of ideal. */
    public static double clamp(double noise) {
        return Math.max(-0.99, noise);
    }
}
