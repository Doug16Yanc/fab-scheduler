package com.fabsim.ui;

import com.fabsim.domain.ToolGroup;
import com.fabsim.engine.DispatchRule;
import com.fabsim.engine.DispatchRules;
import com.fabsim.engine.Metrics;
import com.fabsim.engine.Operation;
import com.fabsim.engine.Scenario;
import com.fabsim.engine.SimulationEngine;
import com.fabsim.engine.Snapshot;

import java.util.List;

/**
 * Roda o motor em uma thread própria, fora da thread do JavaFX, para a tela nunca travar.
 * A interface só enxerga {@link Snapshot}s imutáveis, tirados com o motor "congelado" por um lock.
 */
public final class SimulationRunner {

    /** Janela do Gantt na tela: últimas 24 horas simuladas. */
    public static final double GANTT_WINDOW_MINUTES = 1440;

    private static final int WIP_POINTS = 600;
    private static final long SEED = 42;

    private final Object lock = new Object();
    private final Scenario scenario = Scenario.demo();

    private SimulationEngine engine;
    private DispatchRule rule = DispatchRules.FIFO;

    private volatile boolean running;
    private volatile boolean stopped;
    private volatile double minutesPerSecond = 300;

    public SimulationRunner() {
        engine = new SimulationEngine(scenario, rule, SEED);
        Thread thread = new Thread(this::loop, "fab-simulation");
        thread.setDaemon(true);
        thread.start();
    }

    public List<ToolGroup> toolGroups() {
        return scenario.groups();
    }

    /** Troca a regra e reinicia a simulação, para a comparação partir da mesma semente. */
    public void setRule(DispatchRule newRule) {
        synchronized (lock) {
            rule = newRule;
            engine = new SimulationEngine(scenario, rule, SEED);
        }
    }

    public void reset() {
        synchronized (lock) {
            engine = new SimulationEngine(scenario, rule, SEED);
        }
    }

    public void setRunning(boolean running) {
        this.running = running;
    }

    public boolean isRunning() {
        return running;
    }

    public void setSpeed(double minutesPerSecond) {
        this.minutesPerSecond = minutesPerSecond;
    }

    public void shutdown() {
        stopped = true;
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            return engine.snapshot(WIP_POINTS, GANTT_WINDOW_MINUTES);
        }
    }

    /** Tudo que é preciso para gravar a execução atual. */
    public record RunData(DispatchRule rule, long seed, double horizonDays,
                          Metrics metrics, List<Operation> operations) {
    }

    /** Cópia consistente da execução atual, tirada com o motor congelado; segura para usar em outra thread. */
    public RunData capture() {
        synchronized (lock) {
            return new RunData(rule, SEED, engine.now() / 1440.0, engine.metrics(),
                    List.copyOf(engine.operations()));
        }
    }

    private void loop() {
        long last = System.nanoTime();
        while (!stopped) {
            try {
                Thread.sleep(16);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            long nowNanos = System.nanoTime();
            double dt = (nowNanos - last) / 1e9;
            last = nowNanos;
            if (!running) {
                continue;
            }
            synchronized (lock) {
                engine.runUntil(engine.now() + minutesPerSecond * dt);
            }
        }
    }
}