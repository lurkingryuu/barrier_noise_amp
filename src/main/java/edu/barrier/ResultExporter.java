package edu.barrier;

import edu.barrier.ExperimentRunner.ResultRow;
import java.io.*;
import java.util.List;

/**
 * Exports simulation results to CSV for downstream analysis and plotting.
 *
 * Output:
 *   results/all_results.csv  — one row per (P, B, ε, model) configuration
 */
public class ResultExporter {

    private final String outputDir;

    public ResultExporter(String outputDir) {
        this.outputDir = outputDir;
        new File(outputDir).mkdirs();
    }

    /** Write the full result grid to CSV. */
    public void exportCSV(List<ResultRow> rows) throws IOException {
        String path = outputDir + "/all_results.csv";
        try (PrintWriter pw = new PrintWriter(new FileWriter(path))) {
            pw.println("num_ranks,num_phases,epsilon,noise_model," +
                       "mean_makespan,std_makespan,ci95_makespan," +
                       "mean_idle_waste,std_idle_waste," +
                       "mean_amplification,std_amplification," +
                       "mean_idle_fraction,mean_scaling_efficiency");
            for (ResultRow r : rows) {
                pw.printf("%d,%d,%.4f,%s,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f%n",
                    r.numRanks, r.numPhases, r.epsilon, r.noiseModel.name(),
                    r.meanMakespan, r.stdMakespan, r.ci95Makespan,
                    r.meanIdleWaste, r.stdIdleWaste,
                    r.meanAmplification, r.stdAmplification,
                    r.meanIdleFraction, r.meanScalingEff);
            }
        }
        System.out.println("  → Wrote: " + path);
    }

    /** Print a human-readable summary table to stdout. */
    public void printSummary(List<ResultRow> rows) {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║       BARRIER NOISE AMPLIFICATION — RESULTS SUMMARY          ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");

        System.out.println("\n[ Amplification Factor A  |  B=100, ε=0.10, varying P ]");
        System.out.printf("  %-6s  %-10s  %-10s  %-10s  %-10s%n",
                "P", "NONE", "GAUSSIAN", "LOGNORMAL", "BURSTY");
        System.out.println("  " + "─".repeat(54));
        for (int P : ExperimentRunner.PROCESS_COUNTS) {
            System.out.printf("  %-6d", P);
            for (NoiseInjector.NoiseModel model : NoiseInjector.NoiseModel.values()) {
                rows.stream()
                    .filter(r -> r.numRanks == P && r.numPhases == 100
                              && Math.abs(r.epsilon - 0.10) < 1e-6
                              && r.noiseModel == model)
                    .findFirst()
                    .ifPresent(r -> System.out.printf("  %-10.4f", r.meanAmplification));
            }
            System.out.println();
        }

        System.out.println("\n[ Idle Fraction F  |  P=128, B=100, varying ε ]");
        System.out.printf("  %-6s  %-10s  %-10s  %-10s  %-10s%n",
                "ε", "NONE", "GAUSSIAN", "LOGNORMAL", "BURSTY");
        System.out.println("  " + "─".repeat(54));
        for (double eps : ExperimentRunner.EPSILONS) {
            System.out.printf("  %-6.2f", eps);
            for (NoiseInjector.NoiseModel model : NoiseInjector.NoiseModel.values()) {
                double finalEps = eps;
                rows.stream()
                    .filter(r -> r.numRanks == 128 && r.numPhases == 100
                              && Math.abs(r.epsilon - finalEps) < 1e-6
                              && r.noiseModel == model)
                    .findFirst()
                    .ifPresent(r -> System.out.printf("  %-10.4f", r.meanIdleFraction));
            }
            System.out.println();
        }
        System.out.println();
    }
}
