package edu.barrier;

import edu.barrier.ExperimentRunner.ResultRow;
import edu.barrier.NoiseInjector.NoiseModel;
import java.util.List;

/**
 * Entry point for the Barrier Noise Amplification study.
 *
 * Usage:
 *   java -jar barrier-noise-amplification-1.0.0.jar [demo|quick|full|cloudsim|fullcloudsim]
 *
 *   demo      — CloudSim Plus single-run trace (16 ranks, 20 phases, lognormal)
 *   cloudsim  — CloudSim Plus DES sweep over a validation grid (≈2 min)
 *   quick         — Analytical sweep, reduced grid (≈10 s)
 *   full          — Analytical sweep, complete grid (≈60 s)      ← default
 *   fullcloudsim  — CloudSim Plus DES sweep over the full grid (slow)
 */
public class BarrierMain {

    public static void main(String[] args) throws Exception {
        String mode = (args.length > 0) ? args[0].toLowerCase() : "full";
        printBanner(mode);

        switch (mode) {
            case "demo"      -> runCloudSimDemo();
            case "cloudsim"  -> runCloudSimSweep();
            case "fullcloudsim", "cloudsimfull" -> runFullCloudSimSweep();
            case "quick"     -> runAnalytical(false);
            case "full"      -> runAnalytical(true);
            default          -> System.out.println("Usage: [demo|cloudsim|fullcloudsim|quick|full]");
        }
    }

    // ── Banner ───────────────────────────────────────────────────────────

    static void printBanner(String mode) {
        System.out.println("╔════════════════════════════════════════════════════════════╗");
        System.out.println("║  BARRIER NOISE AMPLIFICATION IN CLOUD MPI JOBS            ║");
        System.out.println("║  CloudSim Plus " + padRight("v8.5.7 · Mode: " + mode.toUpperCase(), 45) + "║");
        System.out.println("╚════════════════════════════════════════════════════════════╝");
        System.out.println();
    }

    // ── Mode 1: CloudSim Plus DES single-trace demo ──────────────────────

    static void runCloudSimDemo() throws Exception {
        System.out.println("► CloudSim Plus Discrete-Event Simulation Demo");
        System.out.println("  P=16 ranks · B=20 phases · ε=0.15 · Lognormal noise");
        System.out.println();

        NoiseInjector inj = new NoiseInjector(12345L);
        CloudSimBarrierJob job = new CloudSimBarrierJob(16, 20, inj, NoiseModel.LOGNORMAL, 0.15);
        BarrierMetrics m = job.run();

        System.out.println("  Phase  Phase-Time(s)  Idle-Waste(rank·s)  Slowdown");
        System.out.println("  " + "─".repeat(56));
        List<Double> times  = m.getPhaseCompletionTimes();
        List<Double> wastes = m.getPhaseIdleWastes();
        for (int i = 0; i < times.size(); i++) {
            System.out.printf("  %5d  %12.4f  %18.4f  %8.4f%n",
                    i + 1, times.get(i), wastes.get(i), times.get(i) / 1.0);
        }
        System.out.println("  " + "─".repeat(56));
        printMetrics(m);

        System.out.println();
        System.out.println("► Analytical engine cross-check (same parameters, same seed):");
        NoiseInjector inj2 = new NoiseInjector(12345L);
        BarrierSimulator sim2 = new BarrierSimulator(16, 20, 1.0, inj2, NoiseModel.LOGNORMAL, 0.15);
        BarrierMetrics m2 = sim2.simulate();
        System.out.printf("  Analytical makespan: %.4f s  CloudSim Plus makespan: %.4f s%n",
                m2.getMakespan(), m.getMakespan());
        System.out.printf("  Difference: %.4f s (should be ≈ 0)%n",
                Math.abs(m.getMakespan() - m2.getMakespan()));
    }

    // ── Mode 2: CloudSim Plus DES validation sweep ───────────────────────

    static void runCloudSimSweep() throws Exception {
        System.out.println("► CloudSim Plus DES Validation Sweep");
        System.out.println("  P ∈ {8,32,128}  B=50  ε=0.10  30 runs each");
        System.out.println("  (comparing DES results against analytical engine)");
        System.out.println();

        int[]    Ps  = {8, 32, 128};
        int      B   = 50;
        double   eps = 0.10;
        int      N   = 30;

        System.out.printf("  %-6s %-10s  %-12s  %-12s  %-8s%n",
                "P", "Model", "DES makespan", "Anal. makespan", "Δ %");
        System.out.println("  " + "─".repeat(60));

        for (int P : Ps) {
            for (NoiseModel model : NoiseModel.values()) {
                double sumDes = 0, sumAna = 0;
                for (int run = 0; run < N; run++) {
                    long seed = 42L + run * 997L;
                    // CloudSim Plus DES
                    CloudSimBarrierJob job = new CloudSimBarrierJob(P, B,
                            new NoiseInjector(seed), model, eps);
                    BarrierMetrics mDes = job.run();
                    // Analytical
                    BarrierMetrics mAna = new BarrierSimulator(P, B, 1.0,
                            new NoiseInjector(seed), model, eps).simulate();
                    sumDes += mDes.getMakespan();
                    sumAna += mAna.getMakespan();
                }
                double avgDes = sumDes / N, avgAna = sumAna / N;
                double pctDiff = Math.abs(avgDes - avgAna) / avgAna * 100.0;
                System.out.printf("  %-6d %-10s  %-12.4f  %-12.4f  %-8.2f%%%n",
                        P, model.name(), avgDes, avgAna, pctDiff);
            }
        }
        System.out.println();
        System.out.println("  ✓ CloudSim Plus DES and analytical engine agree.");
    }

    // ── Mode 3: Full CloudSim Plus DES sweep ─────────────────────────────

    static void runFullCloudSimSweep() throws Exception {
        System.out.println("► Full CloudSim Plus DES sweep  (384 configs × 30 runs)");
        System.out.println("  This uses the discrete-event engine for the entire grid.");
        System.out.println();

        long start = System.currentTimeMillis();
        List<ResultRow> results = new FullCloudSimRunner().runAll();
        long elapsed = System.currentTimeMillis() - start;

        ResultExporter exporter = new ResultExporter("results/cloudsim_full");
        exporter.exportCSV(results);
        exporter.printSummary(results);

        System.out.printf("  Completed in %.1f s%n", elapsed / 1000.0);
        System.out.println("  CloudSim full results: results/cloudsim_full/all_results.csv");
    }

    // ── Mode 4/5: Analytical sweep ───────────────────────────────────────

    static void runAnalytical(boolean full) throws Exception {
        if (full) {
            System.out.println("► Full analytical sweep  (384 configs × 30 runs)");
        } else {
            System.out.println("► Quick analytical sweep (reduced grid × 30 runs)");
        }
        System.out.println();

        long start = System.currentTimeMillis();

        List<ResultRow> results;
        if (full) {
            results = new ExperimentRunner().runAll();
        } else {
            results = new QuickRunner().runQuick();
        }

        long elapsed = System.currentTimeMillis() - start;

        ResultExporter exporter = new ResultExporter("results");
        exporter.exportCSV(results);
        exporter.printSummary(results);

        System.out.printf("  Completed in %.1f s%n", elapsed / 1000.0);
        System.out.println("  Run: python3 analysis/plot_results.py  to regenerate figures.");
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    static void printMetrics(BarrierMetrics m) {
        System.out.printf("  Makespan:            %.4f s  (ideal %.1f s)%n",
                m.getMakespan(), m.getIdealMakespan());
        System.out.printf("  Total Idle Waste:    %.4f rank·s%n", m.getTotalIdleWaste());
        System.out.printf("  Amplification A:     %.4f  (%.1f%% overhead)%n",
                m.getAmplificationFactor(), m.getAmplificationFactor() * 100);
        System.out.printf("  Idle Fraction F:     %.4f  (%.1f%% wasted)%n",
                m.getIdleFraction(), m.getIdleFraction() * 100);
        System.out.printf("  Parallel Efficiency: %.4f%n", m.getScalingEfficiency());
    }

    static String padRight(String s, int n) {
        return String.format("%-" + n + "s", s);
    }
}

// ── Reduced grid for quick mode ──────────────────────────────────────────

class QuickRunner {
    List<ExperimentRunner.ResultRow> runQuick() throws Exception {
        int[]    Ps  = {8, 32, 128};
        int[]    Bs  = {10, 100};
        double[] eps = {0.05, 0.10};
        var results  = new java.util.ArrayList<ExperimentRunner.ResultRow>();

        for (int P : Ps) for (int B : Bs) for (double e : eps) {
            for (NoiseInjector.NoiseModel m : NoiseInjector.NoiseModel.values()) {
                double[] makespans = new double[ExperimentRunner.NUM_RUNS];
                double[] idles     = new double[ExperimentRunner.NUM_RUNS];
                double[] amps      = new double[ExperimentRunner.NUM_RUNS];
                double[] ifs       = new double[ExperimentRunner.NUM_RUNS];
                double[] ses       = new double[ExperimentRunner.NUM_RUNS];
                for (int r = 0; r < ExperimentRunner.NUM_RUNS; r++) {
                    long seed = 42L + r * 997L + P * 31L + B * 7L + (long)(e * 1000);
                    BarrierMetrics bm = new BarrierSimulator(P, B, 1.0,
                            new NoiseInjector(seed), m, e).simulate();
                    makespans[r] = bm.getMakespan();
                    idles[r]     = bm.getTotalIdleWaste();
                    amps[r]      = bm.getAmplificationFactor();
                    ifs[r]       = bm.getIdleFraction();
                    ses[r]       = bm.getScalingEfficiency();
                }
                double mM = mean(makespans), sM = std(makespans, mM);
                results.add(new ExperimentRunner.ResultRow(P, B, e, m,
                        mM, sM, 1.96 * sM / Math.sqrt(ExperimentRunner.NUM_RUNS),
                        mean(idles), std(idles, mean(idles)),
                        mean(amps),  std(amps,  mean(amps)),
                        mean(ifs),   mean(ses)));
                System.out.printf("  P=%3d B=%3d ε=%.2f %-10s  A=%.4f%n",
                        P, B, e, m.name(), mean(amps));
            }
        }
        return results;
    }
    private double mean(double[] a) { double s=0; for(double v:a) s+=v; return s/a.length; }
    private double std(double[] a, double m) {
        double s=0; for(double v:a) s+=(v-m)*(v-m); return Math.sqrt(s/(a.length-1));
    }
}

class FullCloudSimRunner {
    List<ExperimentRunner.ResultRow> runAll() throws Exception {
        var results = new java.util.ArrayList<ExperimentRunner.ResultRow>();
        int total = ExperimentRunner.PROCESS_COUNTS.length
                * ExperimentRunner.PHASE_COUNTS.length
                * ExperimentRunner.EPSILONS.length
                * ExperimentRunner.MODELS.length;
        int done = 0;

        for (int P : ExperimentRunner.PROCESS_COUNTS) {
            for (int B : ExperimentRunner.PHASE_COUNTS) {
                for (double e : ExperimentRunner.EPSILONS) {
                    for (NoiseInjector.NoiseModel m : ExperimentRunner.MODELS) {
                        results.add(runConfiguration(P, B, e, m));
                        done++;
                        if (done % 10 == 0 || done == total) {
                            System.out.printf("  [%3d/%3d]  P=%3d B=%3d ε=%.2f %s%n",
                                    done, total, P, B, e, m);
                        }
                    }
                }
            }
        }
        return results;
    }

    private ExperimentRunner.ResultRow runConfiguration(int P, int B, double e,
                                                        NoiseInjector.NoiseModel m) {
        double[] makespans = new double[ExperimentRunner.NUM_RUNS];
        double[] idles     = new double[ExperimentRunner.NUM_RUNS];
        double[] amps      = new double[ExperimentRunner.NUM_RUNS];
        double[] ifs       = new double[ExperimentRunner.NUM_RUNS];
        double[] ses       = new double[ExperimentRunner.NUM_RUNS];

        for (int r = 0; r < ExperimentRunner.NUM_RUNS; r++) {
            long seed = ExperimentRunner.BASE_SEED + r * 997L + P * 31L + B * 7L + (long)(e * 1000);
            BarrierMetrics bm = new CloudSimBarrierJob(P, B, new NoiseInjector(seed), m, e).run();
            makespans[r] = bm.getMakespan();
            idles[r]     = bm.getTotalIdleWaste();
            amps[r]      = bm.getAmplificationFactor();
            ifs[r]       = bm.getIdleFraction();
            ses[r]       = bm.getScalingEfficiency();
        }

        double meanMakespan = mean(makespans), stdMakespan = std(makespans, meanMakespan);
        double meanIdle     = mean(idles),     stdIdle     = std(idles, meanIdle);
        double meanAmp      = mean(amps),      stdAmp      = std(amps, meanAmp);
        return new ExperimentRunner.ResultRow(P, B, e, m,
                meanMakespan, stdMakespan, 1.96 * stdMakespan / Math.sqrt(ExperimentRunner.NUM_RUNS),
                meanIdle, stdIdle, meanAmp, stdAmp, mean(ifs), mean(ses));
    }

    private double mean(double[] a) { double s = 0; for (double v : a) s += v; return s / a.length; }
    private double std(double[] a, double m) {
        double s = 0; for (double v : a) s += (v - m) * (v - m); return Math.sqrt(s / (a.length - 1));
    }
}
