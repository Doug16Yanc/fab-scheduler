package com.fabsim.experiment;

import com.fabsim.domain.Step;
import com.fabsim.domain.ToolGroup;
import com.fabsim.engine.DispatchRules;
import com.fabsim.engine.Metrics;
import com.fabsim.engine.Scenario;
import com.fabsim.engine.SimulationEngine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.DoubleConsumer;

/**
 * Experimento da curva de Factory Physics: fixa o WIP (CONWIP) e mede throughput e tempo de ciclo para
 * vários níveis de variabilidade nos tempos de processo. Só calcula e devolve dados, então serve tanto
 * para o terminal ({@code WipSweep}) quanto para a janela do desktop.
 */
public final class WipExperiment {

    public static final int[] DEFAULT_WIP_LEVELS = {1, 2, 3, 4, 6, 8, 10, 12, 14, 16, 18, 20, 22, 24, 28, 32, 40, 50, 60};
    public static final double WARMUP_DAYS = 30;
    /** Joelho da curva = primeiro WIP com esta fração da capacidade do gargalo. */
    public static final double KNEE_FRACTION = 0.95;

    /** Média de várias sementes para um par (cv, WIP). */
    public record Point(double cv, int wip, double throughputPerDay, double cycleHours) {
    }

    /** Referências teóricas de Hopp &amp; Spearman para a fábrica do cenário. */
    public record Theory(String bottleneckGroup, double bottleneckLotsPerDay, double t0Hours,
                         double criticalWip, double batchWaitHours) {

        public double throughputBest(int wip) {
            return Math.min(wip * 24.0 / t0Hours, bottleneckLotsPerDay);
        }

        public double throughputPwc(int wip) {
            return wip / (criticalWip + wip - 1) * bottleneckLotsPerDay;
        }

        public double cycleBest(int wip) {
            return Math.max(t0Hours, wip / bottleneckLotsPerDay * 24);
        }

        public double cyclePwc(int wip) {
            return t0Hours + (wip - 1) / bottleneckLotsPerDay * 24;
        }

        /**
         * Gargalo = grupo com menor capacidade: máquinas × disponibilidade ÷ minutos de máquina por lote
         * (batch cheio). T0 = tempo de processo puro; W0 = capacidade do gargalo × T0.
         */
        public static Theory of(Scenario scenario) {
            Map<String, Double> minutesPerLot = new LinkedHashMap<>();
            double batchWaitMinutes = 0;
            Map<String, ToolGroup> groups = new LinkedHashMap<>();
            scenario.groups().forEach(g -> groups.put(g.name(), g));
            for (Step step : scenario.route().steps()) {
                minutesPerLot.merge(step.toolGroup(), step.processMinutes(), Double::sum);
                ToolGroup g = groups.get(step.toolGroup());
                if (g.isBatch()) {
                    batchWaitMinutes += g.maxBatchWaitMinutes();
                }
            }

            String worstGroup = null;
            double worstRate = Double.POSITIVE_INFINITY;
            for (ToolGroup g : scenario.groups()) {
                double perLot = minutesPerLot.getOrDefault(g.name(), 0.0) / g.batchCapacity();
                if (perLot == 0) {
                    continue;
                }
                double availability = g.canFail() ? g.mtbfMinutes() / (g.mtbfMinutes() + g.mttrMinutes()) : 1.0;
                double lotsPerDay = g.machineCount() * availability / perLot * 1440;
                if (lotsPerDay < worstRate) {
                    worstRate = lotsPerDay;
                    worstGroup = g.name();
                }
            }
            double t0Hours = scenario.route().totalProcessMinutes() / 60.0;
            return new Theory(worstGroup, worstRate, t0Hours, worstRate * t0Hours / 24.0, batchWaitMinutes / 60.0);
        }
    }

    /** Resultado completo: para cada cv, um ponto por nível de WIP (na ordem de {@code wipLevels}). */
    public record Result(Theory theory, List<Double> cvs, List<Integer> wipLevels, Map<Double, List<Point>> points) {

        /** Menor WIP que atinge {@link #KNEE_FRACTION} da capacidade do gargalo, se algum atingir. */
        public Optional<Point> knee(double cv) {
            return points.get(cv).stream()
                    .filter(p -> p.throughputPerDay() >= KNEE_FRACTION * theory.bottleneckLotsPerDay())
                    .findFirst();
        }
    }

    private WipExperiment() {
    }

    /**
     * Roda todas as combinações (cv × WIP × semente) em paralelo, uma thread por núcleo.
     *
     * @param progress chamado de threads de trabalho com a fração concluída (0 a 1); pode ser null
     */
    public static Result run(Scenario base, List<Double> cvs, int[] wipLevels, int seeds, double days,
                             DoubleConsumer progress) throws Exception {
        DoubleConsumer report = progress != null ? progress : p -> { };
        int totalRuns = cvs.size() * wipLevels.length * seeds;
        AtomicInteger finished = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors(), task -> {
            Thread thread = new Thread(task, "wip-experiment");
            thread.setDaemon(true); // não segura a JVM se a janela for fechada no meio
            return thread;
        });
        try {
            Map<Double, Map<Integer, List<Future<Metrics>>>> runs = new LinkedHashMap<>();
            for (double cv : cvs) {
                Scenario scenario = base.withProcessTimeCv(cv);
                Map<Integer, List<Future<Metrics>>> perWip = new LinkedHashMap<>();
                for (int wip : wipLevels) {
                    List<Future<Metrics>> futures = new ArrayList<>();
                    for (long seed = 1; seed <= seeds; seed++) {
                        final long s = seed;
                        futures.add(pool.submit(() -> {
                            SimulationEngine engine = new SimulationEngine(scenario, DispatchRules.FIFO, s, wip);
                            engine.runUntil(WARMUP_DAYS * 1440);
                            engine.resetStatistics();
                            engine.runUntil(days * 1440);
                            Metrics metrics = engine.metrics();
                            report.accept(finished.incrementAndGet() / (double) totalRuns);
                            return metrics;
                        }));
                    }
                    perWip.put(wip, futures);
                }
                runs.put(cv, perWip);
            }

            Map<Double, List<Point>> points = new LinkedHashMap<>();
            for (Map.Entry<Double, Map<Integer, List<Future<Metrics>>>> byCv : runs.entrySet()) {
                List<Point> row = new ArrayList<>();
                for (Map.Entry<Integer, List<Future<Metrics>>> byWip : byCv.getValue().entrySet()) {
                    double throughput = 0;
                    double cycle = 0;
                    for (Future<Metrics> f : byWip.getValue()) {
                        Metrics m = f.get();
                        throughput += m.throughputPerDay();
                        cycle += m.avgCycleHours();
                    }
                    row.add(new Point(byCv.getKey(), byWip.getKey(), throughput / seeds, cycle / seeds));
                }
                points.put(byCv.getKey(), row);
            }

            List<Integer> levels = new ArrayList<>();
            for (int wip : wipLevels) {
                levels.add(wip);
            }
            return new Result(Theory.of(base), List.copyOf(cvs), List.copyOf(levels), points);
        } finally {
            pool.shutdown();
        }
    }
}
