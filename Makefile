# ─────────────────────────────────────────────────────────────────────────────
# Barrier Noise Amplification in Cloud MPI Jobs
# Build & Run Makefile
# ─────────────────────────────────────────────────────────────────────────────

JAR     := target/barrier-noise-amplification-1.0.0.jar
PYTHON  := python3
RESULTS := results/all_results.csv
FIGURES := results/figures/fig1_amplification_vs_P.png

.PHONY: all build run demo cloudsim quick full figures clean help

## Default: build + run full experiment + generate figures
all: build full figures

## Compile and package fat JAR
build:
	@echo "Building with Maven..."
	@mvn -q package -DskipTests
	@echo "  ✓ $(JAR)"

## Run the full analytical experiment grid (384 configs × 30 trials)
full: build
	@echo "Running full experiment..."
	@java -jar $(JAR) full

## Run the quick reduced grid (≈10 s)
quick: build
	@echo "Running quick experiment..."
	@java -jar $(JAR) quick

## Run the CloudSim Plus DES single-trace demo
demo: build
	@echo "Running CloudSim Plus demo..."
	@java -jar $(JAR) demo

## Run the CloudSim Plus DES validation sweep vs. analytical engine
cloudsim: build
	@echo "Running CloudSim Plus DES validation sweep..."
	@java -jar $(JAR) cloudsim

## Generate all 7 result figures from results/all_results.csv
figures:
	@echo "Generating figures..."
	@$(PYTHON) analysis/plot_results.py
	@echo "  ✓ Figures saved to results/figures/"

## Remove build artefacts and generated outputs
clean:
	@mvn -q clean
	@rm -rf results/all_results.csv results/figures/
	@echo "Cleaned."

## Show this help
help:
	@echo "Targets: all | build | demo | cloudsim | quick | full | figures | clean"
