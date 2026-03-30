package edu.barrier;

import java.util.ArrayList;
import java.util.List;

/**
 * Collects and computes barrier synchronization metrics for one simulation run.
 *
 * Metric definitions (BSP model):
 *   T_b  = max_i t(i,b)           phase barrier time (straggler sets the clock)
 *   W_b  = Σ_i (T_b − t(i,b))    per-phase barrier idle waste  [rank·s]
 *   M    = Σ_b T_b                total job makespan             [s]
 *   W    = Σ_b W_b                total barrier idle waste       [rank·s]
 *   A    = (M − M_ideal)/M_ideal  noise amplification factor
 *   F    = W / (M·P)              idle fraction
 *   E    = M_ideal / M            parallel efficiency
 */
public class BarrierMetrics {

    private final int    numRanks;
    private final int    numPhases;
    private final double baseComputePerPhase;   // ideal phase time [s]

    private final List<Double> phaseCompletionTimes = new ArrayList<>();
    private final List<Double> phaseIdleWastes      = new ArrayList<>();
    private final List<Double> phaseMaxTimes        = new ArrayList<>();
    private final List<Double> phaseMinTimes        = new ArrayList<>();

    public BarrierMetrics(int numRanks, int numPhases, double baseComputePerPhase) {
        this.numRanks            = numRanks;
        this.numPhases           = numPhases;
        this.baseComputePerPhase = baseComputePerPhase;
    }

    /**
     * Record one barrier crossing.
     * @param rankTimes array[P] of individual rank completion times for this phase
     */
    public void recordPhase(double[] rankTimes) {
        double maxTime = 0, minTime = Double.MAX_VALUE, sumTime = 0;
        for (double t : rankTimes) {
            if (t > maxTime) maxTime = t;
            if (t < minTime) minTime = t;
            sumTime += t;
        }
        double idleWaste = maxTime * numRanks - sumTime;
        phaseCompletionTimes.add(maxTime);
        phaseIdleWastes.add(idleWaste);
        phaseMaxTimes.add(maxTime);
        phaseMinTimes.add(minTime);
    }

    /** M = Σ T_b */
    public double getMakespan() {
        return phaseCompletionTimes.stream().mapToDouble(Double::doubleValue).sum();
    }

    /** M_ideal = B · C */
    public double getIdealMakespan() {
        return numPhases * baseComputePerPhase;
    }

    /** W = Σ W_b */
    public double getTotalIdleWaste() {
        return phaseIdleWastes.stream().mapToDouble(Double::doubleValue).sum();
    }

    /** A = (M − M_ideal) / M_ideal */
    public double getAmplificationFactor() {
        double ideal = getIdealMakespan();
        return (getMakespan() - ideal) / ideal;
    }

    /** F = W / (M · P) */
    public double getIdleFraction() {
        double total = getMakespan() * numRanks;
        return total > 0 ? getTotalIdleWaste() / total : 0.0;
    }

    /** E = M_ideal / M */
    public double getScalingEfficiency() {
        return getIdealMakespan() / getMakespan();
    }

    /** Average per-phase spread = avg(T_b − min_i t(i,b)) */
    public double getAvgPhaseSpread() {
        double sum = 0;
        for (int i = 0; i < phaseMaxTimes.size(); i++) {
            sum += phaseMaxTimes.get(i) - phaseMinTimes.get(i);
        }
        return phaseMaxTimes.isEmpty() ? 0 : sum / phaseMaxTimes.size();
    }

    public int    getNumRanks()  { return numRanks;  }
    public int    getNumPhases() { return numPhases; }
    public List<Double> getPhaseCompletionTimes() { return phaseCompletionTimes; }
    public List<Double> getPhaseIdleWastes()      { return phaseIdleWastes;      }
}
