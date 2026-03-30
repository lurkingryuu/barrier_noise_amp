package edu.barrier;

import edu.barrier.NoiseInjector.NoiseModel;
import java.util.ArrayList;
import java.util.List;

/**
 * Drives the full analytical experiment grid.
 *
 * Grid:
 *   P ∈ {8, 16, 32, 64, 128, 256}   — MPI rank counts
 *   B ∈ {10, 50, 100, 500}           — barrier phase counts
 *   ε ∈ {0.01, 0.05, 0.10, 0.20}    — noise magnitudes
 *   Models: NONE, GAUSSIAN, LOGNORMAL, BURSTY
 *   30 independent trials per configuration (different seeds)
 *
 * Uses the analytical BarrierSimulator (not the CloudSim Plus DES engine)
 * for sweep efficiency. CloudSim Plus DES is used in demo/validation mode.
 * Both engines produce identical results (see BarrierSimulator Javadoc).
 *
 * Statistical reporting: mean ± std ± 95 % CI for each metric.
 */
public class ExperimentRunner {

    static final int[]      PROCESS_COUNTS = {8, 16, 32, 64, 128, 256};
    static final int[]      PHASE_COUNTS   = {10, 50, 100, 500};
    static final double[]   EPSILONS       = {0.01, 0.05, 0.10, 0.20};
    static final NoiseModel[] MODELS       = NoiseModel.values();

    static final int    NUM_RUNS          = 30;
    static final double BASE_COMPUTE_SECS = 1.0;  // ideal time per phase [s]
    static final long   BASE_SEED         = 42L;

    // ── Result row ───────────────────────────────────────────────────────

    public static class ResultRow {
        public final int       numRanks;
        public final int       numPhases;
        public final double    epsilon;
        public final NoiseModel noiseModel;
        public final double    meanMakespan;
        public final double    stdMakespan;
        public final double    ci95Makespan;
        public final double    meanIdleWaste;
        public final double    stdIdleWaste;
        public final double    meanAmplification;
        public final double    stdAmplification;
        public final double    meanIdleFraction;
        public final double    meanScalingEff;

        ResultRow(int numRanks, int numPhases, double epsilon, NoiseModel noiseModel,
                  double meanMakespan, double stdMakespan, double ci95Makespan,
                  double meanIdleWaste, double stdIdleWaste,
                  double meanAmplification, double stdAmplification,
                  double meanIdleFraction, double meanScalingEff) {
            this.numRanks         = numRanks;
            this.numPhases        = numPhases;
            this.epsilon          = epsilon;
            this.noiseModel       = noiseModel;
            this.meanMakespan     = meanMakespan;
            this.stdMakespan      = stdMakespan;
            this.ci95Makespan     = ci95Makespan;
            this.meanIdleWaste    = meanIdleWaste;
            this.stdIdleWaste     = stdIdleWaste;
            this.meanAmplification = meanAmplification;
            this.stdAmplification  = stdAmplification;
            this.meanIdleFraction  = meanIdleFraction;
            this.meanScalingEff   = meanScalingEff;
        }
    }

    // ── Main sweep ───────────────────────────────────────────────────────

    /** Run the complete grid and return all result rows. */
    public List<ResultRow> runAll() {
        List<ResultRow> results = new ArrayList<>();
        int total = PROCESS_COUNTS.length * PHASE_COUNTS.length * EPSILONS.length * MODELS.length;
        int done  = 0;

        for (int P : PROCESS_COUNTS) {
            for (int B : PHASE_COUNTS) {
                for (double eps : EPSILONS) {
                    for (NoiseModel model : MODELS) {
                        results.add(runConfiguration(P, B, eps, model));
                        done++;
                        if (done % 20 == 0 || done == total) {
                            System.out.printf("  [%3d/%3d]  P=%3d B=%3d ε=%.2f %s%n",
                                    done, total, P, B, eps, model);
                        }
                    }
                }
            }
        }
        return results;
    }

    /** Run NUM_RUNS independent trials for one configuration. */
    private ResultRow runConfiguration(int P, int B, double eps, NoiseModel model) {
        double[] makespans      = new double[NUM_RUNS];
        double[] idleWastes     = new double[NUM_RUNS];
        double[] amplifications = new double[NUM_RUNS];
        double[] idleFractions  = new double[NUM_RUNS];
        double[] scalingEffs    = new double[NUM_RUNS];

        for (int run = 0; run < NUM_RUNS; run++) {
            // Deterministic but independent seeds across configurations
            long seed = BASE_SEED + run * 997L + P * 31L + B * 7L + (long)(eps * 1000);
            NoiseInjector injector = new NoiseInjector(seed);
            BarrierSimulator sim   = new BarrierSimulator(P, B, BASE_COMPUTE_SECS,
                                                           injector, model, eps);
            BarrierMetrics m = sim.simulate();
            makespans[run]      = m.getMakespan();
            idleWastes[run]     = m.getTotalIdleWaste();
            amplifications[run] = m.getAmplificationFactor();
            idleFractions[run]  = m.getIdleFraction();
            scalingEffs[run]    = m.getScalingEfficiency();
        }

        double mM  = mean(makespans),   sM  = std(makespans,   mM);
        double mW  = mean(idleWastes),  sW  = std(idleWastes,  mW);
        double mA  = mean(amplifications), sA = std(amplifications, mA);
        double mIF = mean(idleFractions);
        double mSE = mean(scalingEffs);
        double ci95 = 1.960 * sM / Math.sqrt(NUM_RUNS);

        return new ResultRow(P, B, eps, model, mM, sM, ci95, mW, sW, mA, sA, mIF, mSE);
    }

    // ── Statistics helpers ───────────────────────────────────────────────

    private double mean(double[] a) {
        double s = 0; for (double v : a) s += v; return s / a.length;
    }

    private double std(double[] a, double m) {
        double s = 0; for (double v : a) s += (v - m) * (v - m);
        return Math.sqrt(s / (a.length - 1));
    }
}
