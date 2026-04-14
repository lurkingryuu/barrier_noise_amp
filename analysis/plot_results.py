#!/usr/bin/env python3
import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import os, random, math

COLORS = {'NONE':'#4e4e4e','GAUSSIAN':'#1a7fc4','LOGNORMAL':'#e07b39','BURSTY':'#c0392b'}
LABELS = {'NONE':'No Noise (baseline)','GAUSSIAN':'Gaussian','LOGNORMAL':'Lognormal','BURSTY':'Bursty/Spike'}
MODELS = ['NONE','GAUSSIAN','LOGNORMAL','BURSTY']
MARKERS = {'NONE':'D','GAUSSIAN':'s','LOGNORMAL':'o','BURSTY':'^'}
os.makedirs('results/figures', exist_ok=True)
plt.rcParams.update({'font.family':'DejaVu Sans','font.size':11,'axes.spines.top':False,
    'axes.spines.right':False,'axes.grid':True,'grid.alpha':0.3,'grid.linestyle':'--','figure.dpi':150})

def sample_noise(model, epsilon, rng):
    if model=='NONE': return 0.0
    elif model=='GAUSSIAN': return epsilon*rng.gauss(0,1)
    elif model=='LOGNORMAL':
        sigma=math.sqrt(math.log(1+epsilon**2)); mu=-0.5*sigma**2
        return math.exp(mu+sigma*rng.gauss(0,1))-1.0
    elif model=='BURSTY':
        return epsilon*5.0*(1+0.2*rng.gauss(0,1)) if rng.random()<0.05 else epsilon*0.05*rng.gauss(0,1)

def clamp(v): return max(-0.99,v)

def simulate_run(P,B,epsilon,model,seed,base=1.0):
    rng=random.Random(seed); total_m=0.0; total_w=0.0
    for _ in range(B):
        times=[base*(1+clamp(sample_noise(model,epsilon,rng))) for _ in range(P)]
        tmax=max(times); total_m+=tmax; total_w+=sum(tmax-t for t in times)
    ideal=B*base
    return total_m,total_w,(total_m-ideal)/ideal

NRUNS=30

# Fig 1: Amplification vs P
print("Fig 1..."); P_vals=[8,16,32,64,128,256]; B,eps=100,0.10
fig,ax=plt.subplots(figsize=(7,4.5))
for m in MODELS:
    amps=[[simulate_run(P,B,eps,m,42+r*997)[2] for r in range(NRUNS)] for P in P_vals]
    means=[np.mean(a) for a in amps]; ci=[1.96*np.std(a,ddof=1)/math.sqrt(NRUNS) for a in amps]
    ax.errorbar(P_vals,means,yerr=ci,color=COLORS[m],marker=MARKERS[m],linewidth=2,markersize=7,capsize=4,label=LABELS[m])
ax.set_xlabel('Number of MPI Ranks (P)',fontsize=12); ax.set_ylabel('Amplification Factor A',fontsize=11)
ax.set_title('Noise Amplification Factor vs. Process Count\n(B=100 phases, ε=0.10, 30 runs each)',fontsize=12)
ax.set_xscale('log',base=2); ax.set_xticks(P_vals); ax.set_xticklabels(P_vals)
ax.legend(loc='upper left',framealpha=0.9,fontsize=10); ax.set_ylim(bottom=-0.02)
plt.tight_layout(); plt.savefig('results/figures/fig1_amplification_vs_P.png'); plt.close()

# Fig 2: Idle Waste vs P
print("Fig 2...")
fig,ax=plt.subplots(figsize=(7,4.5))
for m in MODELS:
    ws=[[simulate_run(P,B,eps,m,42+r*997)[1] for r in range(NRUNS)] for P in P_vals]
    means=[np.mean(w) for w in ws]; ci=[1.96*np.std(w,ddof=1)/math.sqrt(NRUNS) for w in ws]
    ax.errorbar(P_vals,means,yerr=ci,color=COLORS[m],marker=MARKERS[m],linewidth=2,markersize=7,capsize=4,label=LABELS[m])
ax.set_xlabel('Number of MPI Ranks (P)',fontsize=12); ax.set_ylabel('Total Barrier Idle Waste W (rank·s)',fontsize=11)
ax.set_title('Total Idle Waste vs. Process Count\n(B=100, ε=0.10)',fontsize=12)
ax.set_xscale('log',base=2); ax.set_xticks(P_vals); ax.set_xticklabels(P_vals)
ax.legend(loc='upper left',framealpha=0.9,fontsize=10)
plt.tight_layout(); plt.savefig('results/figures/fig2_idle_waste_vs_P.png'); plt.close()

# Fig 3: Amplification vs epsilon
print("Fig 3..."); eps_vals=[0.01,0.05,0.10,0.20]; P,B=64,100
fig,ax=plt.subplots(figsize=(7,4.5))
for m in MODELS:
    amps=[[simulate_run(P,B,e,m,42+r*997)[2] for r in range(NRUNS)] for e in eps_vals]
    means=[np.mean(a) for a in amps]; ci=[1.96*np.std(a,ddof=1)/math.sqrt(NRUNS) for a in amps]
    ax.errorbar(eps_vals,means,yerr=ci,color=COLORS[m],marker=MARKERS[m],linewidth=2,markersize=7,capsize=4,label=LABELS[m])
ax.set_xlabel('Noise Magnitude ε',fontsize=12); ax.set_ylabel('Amplification Factor A',fontsize=11)
ax.set_title('Amplification vs. Noise Magnitude\n(P=64, B=100)',fontsize=12)
ax.legend(loc='upper left',framealpha=0.9,fontsize=10)
plt.tight_layout(); plt.savefig('results/figures/fig3_amplification_vs_epsilon.png'); plt.close()

# Fig 4: Boxplot
print("Fig 4..."); P,B,eps=64,100,0.10
all_ms={m:[simulate_run(P,B,eps,m,42+r*997)[0] for r in range(NRUNS)] for m in MODELS}
fig,ax=plt.subplots(figsize=(7,4.5))
bp=ax.boxplot([all_ms[m] for m in MODELS],patch_artist=True,notch=False,medianprops=dict(color='black',linewidth=2))
for patch,m in zip(bp['boxes'],MODELS): patch.set_facecolor(COLORS[m]); patch.set_alpha(0.8)
ax.set_xticks(range(1,5)); ax.set_xticklabels([LABELS[m] for m in MODELS],fontsize=10)
ax.set_ylabel('Total Makespan (seconds)',fontsize=11)
ax.set_title('Makespan Distribution across 30 Runs\n(P=64, B=100, ε=0.10)',fontsize=12)
ax.axhline(B*1.0,color='grey',linestyle='--',linewidth=1.2,label='Ideal makespan'); ax.legend(fontsize=10)
plt.tight_layout(); plt.savefig('results/figures/fig4_boxplot_makespan.png'); plt.close()

# Fig 5: Parallel efficiency vs P
print("Fig 5..."); P_vals=[8,16,32,64,128,256]; B,eps=100,0.10
fig,ax=plt.subplots(figsize=(7,4.5))
for m in MODELS:
    effs=[]
    for P in P_vals:
        runs=[simulate_run(P,B,eps,m,42+r*997) for r in range(NRUNS)]
        ideal=B*1.0; effs.append(np.mean([ideal/ms for ms,_,_ in runs]))
    ax.plot(P_vals,effs,color=COLORS[m],marker=MARKERS[m],linewidth=2,markersize=7,label=LABELS[m])
ax.axhline(1.0,color='grey',linestyle='--',linewidth=1,label='Perfect efficiency')
ax.set_xlabel('Number of MPI Ranks (P)',fontsize=12); ax.set_ylabel('Parallel Efficiency E = M_ideal/M',fontsize=11)
ax.set_title('Parallel Efficiency vs. Process Count\n(B=100, ε=0.10)',fontsize=12)
ax.set_xscale('log',base=2); ax.set_xticks(P_vals); ax.set_xticklabels(P_vals)
ax.set_ylim(0,1.1); ax.legend(loc='lower left',framealpha=0.9,fontsize=10)
plt.tight_layout(); plt.savefig('results/figures/fig5_parallel_efficiency.png'); plt.close()

# Fig 6: Heatmap
print("Fig 6..."); P_arr=[8,32,128,256]; eps_arr=[0.01,0.05,0.10,0.20]
fig,axes=plt.subplots(1,4,figsize=(9,7.1),sharey=True)
for ax_i,model in enumerate(MODELS):
    Z=np.zeros((len(eps_arr),len(P_arr)))
    for ei,e in enumerate(eps_arr):
        for pi,P in enumerate(P_arr):
            runs=[simulate_run(P,100,e,model,42+r*997) for r in range(NRUNS)]
            Z[ei,pi]=np.mean([w/(ms*P) if ms>0 else 0 for ms,w,_ in runs])
    im=axes[ax_i].imshow(Z,cmap='YlOrRd',vmin=0,vmax=0.45,aspect='auto',origin='lower')
    axes[ax_i].set_xticks(range(len(P_arr))); axes[ax_i].set_xticklabels(P_arr,fontsize=10)
    axes[ax_i].set_title(LABELS[model],fontsize=11); axes[ax_i].set_xlabel('P (ranks)',fontsize=10)
    if ax_i==0:
        axes[ax_i].set_yticks(range(len(eps_arr))); axes[ax_i].set_yticklabels([str(e) for e in eps_arr],fontsize=10)
        axes[ax_i].set_ylabel('Noise ε',fontsize=11)
fig.suptitle('Idle Fraction Heatmap (B=100)',fontsize=12,y=0.99)
fig.colorbar(im,ax=axes.ravel().tolist(),shrink=0.75,label='Idle Fraction')
plt.tight_layout(); plt.savefig('results/figures/fig6_heatmap_idle_fraction.png',bbox_inches='tight'); plt.close()

# Fig 7: Phase trace
print("Fig 7..."); P2,B2,eps2,model2=16,20,0.15,'LOGNORMAL'
rng2=random.Random(12345); phase_times=[]; idle_wastes2=[]
for _ in range(B2):
    times=[1.0*(1+clamp(sample_noise(model2,eps2,rng2))) for _ in range(P2)]
    tmax=max(times); phase_times.append(tmax); idle_wastes2.append(sum(tmax-t for t in times))
fig,(ax1,ax2)=plt.subplots(2,1,figsize=(7,6),sharex=True)
phases=list(range(1,B2+1))
ax1.bar(phases,phase_times,color=COLORS['LOGNORMAL'],alpha=0.8)
ax1.axhline(1.0,color='grey',linestyle='--',linewidth=1.5,label='Ideal (1.0 s)'); ax1.legend(fontsize=10)
ax1.set_ylabel('Phase Time (s)',fontsize=11); ax1.set_title('Phase-by-Phase Barrier Times (P=16, B=20, ε=0.15, Lognormal)',fontsize=11)
ax2.bar(phases,idle_wastes2,color='#c0392b',alpha=0.75)
ax2.set_xlabel('Phase Number',fontsize=11); ax2.set_ylabel('Idle Waste (rank·s)',fontsize=11)
plt.tight_layout(); plt.savefig('results/figures/fig7_phase_trace.png'); plt.close()

print("\nAll 7 figures saved to analysis/figures/")
