package edu.barrier;

import edu.barrier.NoiseInjector.NoiseModel;

import org.cloudsimplus.brokers.DatacenterBrokerSimple;
import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.cloudlets.CloudletSimple;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.core.CloudSimTag;
import org.cloudsimplus.core.events.SimEvent;
import org.cloudsimplus.datacenters.DatacenterSimple;
import org.cloudsimplus.hosts.Host;
import org.cloudsimplus.hosts.HostSimple;
import org.cloudsimplus.resources.Pe;
import org.cloudsimplus.resources.PeSimple;
import org.cloudsimplus.utilizationmodels.UtilizationModelFull;
import org.cloudsimplus.vms.Vm;
import org.cloudsimplus.vms.VmSimple;

import java.util.ArrayList;
import java.util.List;

/**
 * CloudSim Plus Discrete-Event Simulation of a Barrier-Synchronized MPI Job.
 *
 * Architecture:
 *   One DatacenterSimple with a single HostSimple having P processing elements.
 *   P VmSimple instances (one per MPI rank) each at 1000 MIPS.
 *   A BarrierBroker (extends DatacenterBrokerSimple) drives the BSP loop:
 *     Phase b:
 *       1. Submit P cloudlets with lengths 1000*(1+η) MI (noisy)
 *       2. Each cloudlet executes on its dedicated VM in ~(1+η) seconds
 *       3. Override processEvent() to catch CLOUDLET_RETURN tags
 *       4. Count completions; when all P return → record phase → submit b+1
 *
 * CloudSim Plus ↔ MPI mapping:
 *   VmSimple                     ↔  MPI rank
 *   CloudletSimple (per phase)   ↔  compute superstep
 *   cloudlet.length / vm.mips    ↔  phase execution time t(i,b)
 *   MPI_Barrier                  ↔  BarrierBroker phase-completion counter
 *
 * Equivalence with analytical engine (BarrierSimulator):
 *   Base MIPS = 1000, base MI = 1000 → ideal time = 1.0 s per phase.
 *   Noisy MI = 1000*(1+η) → exec time = (1+η) s  ≡  BASE_COMPUTE*(1+η). ∎
 */
public class CloudSimBarrierJob {

    private static final long BASE_MIPS     = 1_000;
    private static final long BASE_MI       = 1_000;
    private static final long HOST_RAM      = 16_384;
    private static final long HOST_BW       = 10_000;
    private static final long HOST_STORAGE  = 1_000_000;
    private static final long VM_RAM        = 512;
    private static final long VM_BW         = 1_000;
    private static final long VM_SIZE       = 10_000;

    private final int          numRanks;
    private final int          numPhases;
    private final NoiseInjector injector;
    private final NoiseModel   noiseModel;
    private final double       epsilon;

    public CloudSimBarrierJob(int numRanks, int numPhases,
                               NoiseInjector injector, NoiseModel noiseModel,
                               double epsilon) {
        this.numRanks   = numRanks;
        this.numPhases  = numPhases;
        this.injector   = injector;
        this.noiseModel = noiseModel;
        this.epsilon    = epsilon;
    }

    /**
     * Run the full CloudSim Plus DES and return collected BarrierMetrics.
     */
    public BarrierMetrics run() {
        BarrierMetrics metrics = new BarrierMetrics(
                numRanks, numPhases, (double) BASE_MI / BASE_MIPS);

        CloudSimPlus simulation = new CloudSimPlus();

        // One host per VM so allocation always succeeds regardless of numRanks.
        // Each host has exactly 1 PE at BASE_MIPS — models one cloud VM slot.
        List<Host> hosts = new ArrayList<>(numRanks);
        for (int i = 0; i < numRanks; i++) {
            hosts.add(new HostSimple(HOST_RAM, HOST_BW, HOST_STORAGE,
                                     List.of(new PeSimple(BASE_MIPS))));
        }
        new DatacenterSimple(simulation, hosts);

        // One VM per rank, each gets 1 PE at BASE_MIPS
        List<Vm> vms = new ArrayList<>(numRanks);
        for (int i = 0; i < numRanks; i++) {
            vms.add(new VmSimple(BASE_MIPS, 1)
                    .setRam(VM_RAM).setBw(VM_BW).setSize(VM_SIZE));
        }

        // Custom barrier broker
        BarrierBroker broker = new BarrierBroker(
                simulation, numRanks, numPhases, injector, noiseModel, epsilon, metrics, vms);
        broker.submitVmList(vms);

        // Submit phase-0 cloudlets to kick off the simulation
        broker.submitCloudletList(broker.buildPhaseCloudlets(0, vms));

        simulation.start();
        return metrics;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  BarrierBroker — MPI_Barrier coordinator
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Custom broker implementing MPI barrier semantics inside CloudSim Plus DES.
     *
     * We override {@code processEvent()} to intercept CLOUDLET_RETURN tags.
     * The parent class handles all other logic (VM creation, placement, etc.).
     *
     * On each CLOUDLET_RETURN:
     *   - Decode phase and rank from cloudlet ID
     *   - Record relative finish time for that rank
     *   - When all P ranks finish phase b:
     *       → metrics.recordPhase(rankTimes)
     *       → if b+1 < B: phaseStartTime = clock; submit phase b+1 cloudlets
     */
    static class BarrierBroker extends DatacenterBrokerSimple {

        private final int          numRanks;
        private final int          numPhases;
        private final NoiseInjector injector;
        private final NoiseModel   noiseModel;
        private final double       epsilon;
        private final BarrierMetrics metrics;
        private final List<Vm>     vms;

        private int    currentPhase         = 0;
        private int    completionsThisPhase = 0;
        private double phaseStartTime       = 0.0;
        private double[] rankTimes;  // relative exec times for current phase

        BarrierBroker(CloudSimPlus sim, int numRanks, int numPhases,
                      NoiseInjector injector, NoiseModel noiseModel,
                      double epsilon, BarrierMetrics metrics, List<Vm> vms) {
            super(sim);
            this.numRanks   = numRanks;
            this.numPhases  = numPhases;
            this.injector   = injector;
            this.noiseModel = noiseModel;
            this.epsilon    = epsilon;
            this.metrics    = metrics;
            this.vms        = vms;
            this.rankTimes  = new double[numRanks];
        }

        @Override
        public void processEvent(final SimEvent evt) {
            // Intercept cloudlet-return events before the parent handles bookkeeping
            if (evt.getTag() == CloudSimTag.CLOUDLET_RETURN) {
                Object data = evt.getData();
                if (data instanceof Cloudlet cl) {
                    int phase = (int)(cl.getId() / numRanks);
                    int rank  = (int)(cl.getId() % numRanks);

                    if (phase == currentPhase) {
                        double now           = getSimulation().clock();
                        double relativeTime  = Math.max(0.0, now - phaseStartTime);
                        rankTimes[rank]      = relativeTime;
                        completionsThisPhase++;

                        if (completionsThisPhase == numRanks) {
                            // ── All P ranks crossed the barrier ─────────────
                            metrics.recordPhase(rankTimes.clone());

                            currentPhase++;
                            if (currentPhase < numPhases) {
                                phaseStartTime       = getSimulation().clock();
                                completionsThisPhase = 0;
                                rankTimes            = new double[numRanks];
                                // Submit next phase's cloudlets into the running simulation
                                submitCloudletList(buildPhaseCloudlets(currentPhase, vms));
                            }
                        }
                    }
                }
            }
            // Always call super to maintain CloudSim Plus internal bookkeeping
            super.processEvent(evt);
        }

        /** Build P cloudlets for a given phase, each bound to its rank's VM. */
        List<Cloudlet> buildPhaseCloudlets(int phase, List<Vm> vms) {
            List<Cloudlet> cloudlets = new ArrayList<>(numRanks);
            for (int rank = 0; rank < numRanks; rank++) {
                double eta    = injector.sample(noiseModel, epsilon);
                long   length = Math.max(1L, Math.round(BASE_MI * (1.0 + eta)));
                Cloudlet cl   = new CloudletSimple(length, 1, new UtilizationModelFull());
                // Encode phase + rank into cloudlet ID for decoding in processEvent
                cl.setId((long) phase * numRanks + rank);
                if (rank < vms.size()) cl.setVm(vms.get(rank));
                cloudlets.add(cl);
            }
            return cloudlets;
        }
    }
}
