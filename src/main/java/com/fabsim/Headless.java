package com.fabsim;

import com.fabsim.engine.DispatchRules;
import com.fabsim.engine.Metrics;
import com.fabsim.engine.Scenario;
import com.fabsim.engine.SimulationEngine;
import com.fabsim.persistence.RunRepository;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Roda o mesmo cenário com cada regra de despacho, em várias sementes aleatórias e em paralelo,
 * e imprime a média dos indicadores. Uso: {@code Headless [sementes] [dias]}.
 */
public final class Headless {

    private Headless() {
    }

    public static void main(String[] args) throws Exception {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        int seeds = args.length > 0 ? Integer.parseInt(args[0]) : 8;
        double days = args.length > 1 ? Double.parseDouble(args[1]) : 120;

        final RunRepository repository = RunRepository.fromEnv(); // null sem FAB_DB_URL
        if (repository != null) {
            repository.initSchema();
        }

        ExecutorService pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        Map<DispatchRules, List<Future<Metrics>>> runs = new EnumMap<>(DispatchRules.class);
        for (DispatchRules rule : DispatchRules.values()) {
            List<Future<Metrics>> futures = new ArrayList<>();
            for (long seed = 1; seed <= seeds; seed++) {
                final long s = seed;
                futures.add(pool.submit(() -> {
                    SimulationEngine engine = new SimulationEngine(Scenario.demo(), rule, s);
                    engine.runUntil(days * 1440);
                    Metrics metrics = engine.metrics();
                    if (repository != null) {
                        repository.save(rule, s, days, metrics, engine.operations());
                    }
                    return metrics;
                }));
            }
            runs.put(rule, futures);
        }

        out.printf("Cenário demo: %d sementes, %.0f dias simulados por execução%n%n", seeds, days);
        out.printf("%-28s %10s %12s %10s %12s %10s%n",
                "Regra", "Ciclo (h)", "Atraso (h)", "% atras.", "Viol. QT", "Lotes/dia");

        for (Map.Entry<DispatchRules, List<Future<Metrics>>> entry : runs.entrySet()) {
            double cycle = 0, tardiness = 0, tardy = 0, violations = 0, throughput = 0;
            for (Future<Metrics> f : entry.getValue()) {
                Metrics m = f.get();
                cycle += m.avgCycleHours();
                tardiness += m.avgTardinessHours();
                tardy += m.tardyPercent();
                violations += m.queueTimeViolations();
                throughput += m.throughputPerDay();
            }
            out.printf("%-28s %10.1f %12.1f %10.1f %12.1f %10.2f%n",
                    entry.getKey().label(), cycle / seeds, tardiness / seeds, tardy / seeds,
                    violations / seeds, throughput / seeds);
        }
        pool.shutdown();
        if (repository == null) {
            out.println("AVISO: banco desativado (FAB_DB_URL não encontrada no ambiente nem em .env, "
                    + "diretório atual: " + System.getProperty("user.dir") + "). Nada será gravado.");
        } else {
            repository.initSchema();
        }
    }
}
