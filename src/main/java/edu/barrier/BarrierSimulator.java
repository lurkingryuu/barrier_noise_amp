package edu.barrier;

import edu.barrier.NoiseInjector.NoiseModel;

/**
 * Analytical (fast) BSP barrier simulator.
 *
 * Implements the Bulk-Synchronous Parallel execution model:
 *   - P ranks each execute a compute phase independently
 *   - All ranks synchronize at a global barrier (straggler effect)
 *   - Phase time T_b = max_i t(i,b)
 *
 * This is mathematically equivalent to the CloudSim Plus discrete-event
 * simulation (see CloudSimBarrierJob). The analytical engine is used for
 * the full parameter sweep (384 configs × 30 runs) because CloudSim Plus
 * DES adds ~10–50 ms overhead per run; analytically the same 30-run sweep
 * finishes in milliseconds.
 *
 * Equivalence proof:
 *   CloudSim Plus: cloudlet exec time = cloudlet.getLength() / vm.getMips()
 *   This code:     rankTime(i,b)      = BASE_COMPUTE * (1 + η)
 *   With BASE_MIPS=1000, BASE_LENGTH=1000: both = (1 + η) seconds. ∎
 */
public class BarrierSimulator {

    private final int         numRanks;
    private final int         numPhases;
    private final double      baseComputeSeconds;
    private final NoiseInjector injector;
    private final NoiseModel  noiseModel;
    private final double      epsilon;

    /**
     * @param numRanks           number of MPI ranks (P)
     * @param numPhases          number of barrier-separated supersteps (B)
     * @param baseComputeSeconds ideal compute time per rank per phase (C)
     * @param injector           noise sampler
     * @param noiseModel         which distribution to use
     * @param epsilon            noise magnitude (e.g. 0.10 = 10 %)
     */
    public BarrierSimulator(int numRanks, int numPhases, double baseComputeSeconds,
                             NoiseInjector injector, NoiseModel noiseModel, double epsilon) {
        this.numRanks           = numRanks;
        this.numPhases          = numPhases;
        this.baseComputeSeconds = baseComputeSeconds;
        this.injector           = injector;
        this.noiseModel         = noiseModel;
        this.epsilon            = epsilon;
    }

    /**
     * Run the full job and return collected metrics.
     *
     * For each phase b ∈ [0, B):
     *   1. Sample η_i ~ NoiseModel(ε) for each rank i
     *   2. t(i,b) = C · (1 + η_i)
     *   3. T_b    = max_i t(i,b)   ← barrier completion (straggler)
     *   4. W_b    = Σ_i (T_b − t(i,b))  ← idle waste this phase
     */
    public BarrierMetrics simulate() {
        BarrierMetrics metrics  = new BarrierMetrics(numRanks, numPhases, baseComputeSeconds);
        double[]       rankTimes = new double[numRanks];

        for (int phase = 0; phase < numPhases; phase++) {
            for (int rank = 0; rank < numRanks; rank++) {
                double eta = injector.sample(noiseModel, epsilon);
                rankTimes[rank] = baseComputeSeconds * (1.0 + eta);
            }
            metrics.recordPhase(rankTimes);
        }
        return metrics;
    }
}
