package com.fabsim.engine;

import com.fabsim.domain.Lot;
import com.fabsim.domain.Machine;
import com.fabsim.domain.Step;
import com.fabsim.domain.ToolGroup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;

/**
 * Simulação de eventos discretos de uma fab. O tempo é em minutos e só avança de evento em evento.
 * Não é thread-safe: quem rodar em outra thread precisa sincronizar o acesso.
 *
 * <p>Simplificação: uma falha que cai com a máquina ocupada só vale quando o processo termina
 * (o equipamento não interrompe o lote no meio).
 */
public final class SimulationEngine {

    private enum Type { RELEASE, STEP_END, BATCH_CHECK, FAIL, REPAIR, SAMPLE }

    private record Event(double time, long seq, Type type, Object payload) implements Comparable<Event> {
        @Override
        public int compareTo(Event other) {
            int c = Double.compare(time, other.time);
            return c != 0 ? c : Long.compare(seq, other.seq);
        }
    }

    private static final double SAMPLE_INTERVAL_MINUTES = 60;
    private static final double EPS = 1e-9;

    private final Scenario scenario;
    private final DispatchRule rule;
    private final Random rng;
    private final int conwip;

    private final PriorityQueue<Event> events = new PriorityQueue<>();
    private final Map<String, ToolGroup> groups = new LinkedHashMap<>();
    private final Map<String, List<Machine>> machinesByGroup = new LinkedHashMap<>();
    private final Map<String, List<Lot>> queues = new LinkedHashMap<>();
    private final Map<String, Double> pendingBatchChecks = new HashMap<>();
    private final List<Machine> allMachines = new ArrayList<>();
    private final List<double[]> wipSeries = new ArrayList<>();
    private final List<Operation> operations = new ArrayList<>(); // em ordem de início
    private double maxStepMinutes; // maior duração vista; limita a busca de operações recentes no Gantt

    private double now;
    private double statsStart; // início da janela de medição (muda em resetStatistics)
    private long seq;
    private int nextLotId = 1;
    private int released;
    private int completed;
    private int wip;
    private int violations;
    private int tardyLots;
    private double cycleSum;
    private double tardinessSum;

    public SimulationEngine(Scenario scenario, DispatchRule rule, long seed) {
        this(scenario, rule, seed, 0);
    }

    /**
     * @param conwip 0 = liberação aberta (chegadas aleatórias, como no cenário demo); maior que 0 = WIP
     *               constante: começa com {@code conwip} lotes e cada lote concluído libera um novo.
     *               É assim que se mede a curva WIP × throughput × tempo de ciclo.
     */
    public SimulationEngine(Scenario scenario, DispatchRule rule, long seed, int conwip) {
        this.scenario = scenario;
        this.rule = rule;
        this.conwip = conwip;
        this.rng = new Random(seed);
        this.maxStepMinutes = scenario.route().steps().stream().mapToDouble(Step::processMinutes).max().orElse(0);

        for (ToolGroup g : scenario.groups()) {
            groups.put(g.name(), g);
            queues.put(g.name(), new ArrayList<>());
            List<Machine> machines = new ArrayList<>();
            for (int i = 1; i <= g.machineCount(); i++) {
                Machine m = new Machine(g.name() + "-" + i, g.name());
                machines.add(m);
                allMachines.add(m);
                if (g.canFail()) {
                    schedule(exponential(g.mtbfMinutes()), Type.FAIL, m);
                }
            }
            machinesByGroup.put(g.name(), machines);
        }
        if (conwip > 0) {
            for (int i = 0; i < conwip; i++) {
                createLot();
            }
            for (String group : groups.keySet()) {
                dispatch(group);
            }
        } else {
            schedule(0, Type.RELEASE, null);
        }
        schedule(0, Type.SAMPLE, null);
    }

    // ---------------------------------------------------------------- avanço do tempo

    /** Processa todos os eventos até o instante {@code target} (em minutos) e posiciona o relógio nele. */
    public void runUntil(double target) {
        while (!events.isEmpty() && events.peek().time() <= target) {
            Event e = events.poll();
            now = e.time();
            handle(e);
        }
        now = Math.max(now, target);
    }

    private void handle(Event e) {
        switch (e.type()) {
            case RELEASE -> onRelease();
            case STEP_END -> onStepEnd((Machine) e.payload());
            case BATCH_CHECK -> dispatch((String) e.payload());
            case FAIL -> onFail((Machine) e.payload());
            case REPAIR -> onRepair((Machine) e.payload());
            case SAMPLE -> {
                wipSeries.add(new double[]{now, wip});
                schedule(now + SAMPLE_INTERVAL_MINUTES, Type.SAMPLE, null);
            }
        }
    }

    private void schedule(double time, Type type, Object payload) {
        events.add(new Event(time, seq++, type, payload));
    }

    private double exponential(double mean) {
        return -mean * Math.log(1 - rng.nextDouble());
    }

    // ---------------------------------------------------------------- eventos

    private void onRelease() {
        String group = createLot();
        schedule(now + exponential(scenario.meanInterArrivalMinutes()), Type.RELEASE, null);
        dispatch(group);
    }

    /** Cria um lote na fila da primeira etapa e devolve o grupo dessa etapa (quem chama despacha). */
    private String createLot() {
        boolean hot = rng.nextDouble() < scenario.hotLotFraction();
        double touch = scenario.route().totalProcessMinutes();
        double due = now + touch * (hot ? scenario.hotDueFactor() : scenario.dueFactor());
        Lot lot = new Lot(nextLotId++, scenario.route(), now, due, hot);
        released++;
        wip++;

        String group = lot.currentStep().toolGroup();
        queues.get(group).add(lot);
        return group;
    }

    private void onStepEnd(Machine m) {
        Set<String> touched = new LinkedHashSet<>();
        for (Lot lot : m.finishProcessing()) {
            lot.completeStep(now);
            if (lot.isDone()) {
                wip--;
                completed++;
                double cycle = lot.finishTime() - lot.releaseTime();
                double tardiness = Math.max(0, lot.finishTime() - lot.dueDate());
                cycleSum += cycle;
                tardinessSum += tardiness;
                if (tardiness > 0) {
                    tardyLots++;
                }
                if (conwip > 0) {
                    touched.add(createLot()); // WIP constante: lote que sai, lote que entra
                }
            } else {
                String next = lot.currentStep().toolGroup();
                queues.get(next).add(lot);
                touched.add(next);
            }
        }
        if (m.pendingFailure()) {
            m.setPendingFailure(false);
            goDown(m);
        }
        touched.add(m.group());
        for (String group : touched) {
            dispatch(group);
        }
    }

    private void onFail(Machine m) {
        switch (m.state()) {
            case IDLE -> goDown(m);
            case BUSY -> m.setPendingFailure(true);
            case DOWN -> { /* já está quebrada */ }
        }
    }

    private void goDown(Machine m) {
        m.setDown();
        schedule(now + exponential(groups.get(m.group()).mttrMinutes()), Type.REPAIR, m);
    }

    private void onRepair(Machine m) {
        m.setIdle();
        schedule(now + exponential(groups.get(m.group()).mtbfMinutes()), Type.FAIL, m);
        dispatch(m.group());
    }

    // ---------------------------------------------------------------- despacho

    /** Tenta colocar lotes da fila do grupo em todas as máquinas ociosas. */
    private void dispatch(String group) {
        ToolGroup tg = groups.get(group);
        List<Lot> queue = queues.get(group);
        for (Machine m : machinesByGroup.get(group)) {
            if (queue.isEmpty()) {
                return;
            }
            if (m.state() != Machine.State.IDLE) {
                continue;
            }
            List<Lot> batch = pickBatch(tg, queue);
            if (batch == null) {
                return; // nada pronto para sair agora (batch ainda enchendo)
            }
            start(m, batch);
        }
    }

    /** Escolhe o que a próxima máquina livre vai processar, ou null se ainda vale esperar. */
    private List<Lot> pickBatch(ToolGroup tg, List<Lot> queue) {
        if (!tg.isBatch()) {
            Lot best = null;
            double bestScore = Double.POSITIVE_INFINITY;
            for (Lot lot : queue) {
                double s = rule.score(lot, now);
                if (best == null || s < bestScore) {
                    best = lot;
                    bestScore = s;
                }
            }
            return List.of(best);
        }

        // Batch: só dá para juntar lotes da mesma receita (mesmo nome de etapa).
        Map<String, List<Lot>> byRecipe = new LinkedHashMap<>();
        for (Lot lot : queue) {
            byRecipe.computeIfAbsent(lot.currentStep().name(), k -> new ArrayList<>()).add(lot);
        }

        List<Lot> chosen = null;
        double chosenScore = Double.POSITIVE_INFINITY;
        for (Map.Entry<String, List<Lot>> entry : byRecipe.entrySet()) {
            List<Lot> candidates = entry.getValue();
            candidates.sort(Comparator.comparingDouble(l -> rule.score(l, now)));

            double oldestReady = Double.POSITIVE_INFINITY;
            for (Lot l : candidates) {
                oldestReady = Math.min(oldestReady, l.readyTime());
            }
            boolean full = candidates.size() >= tg.batchCapacity();
            boolean timedOut = now - oldestReady >= tg.maxBatchWaitMinutes() - EPS;

            if (full || timedOut) {
                double s = rule.score(candidates.get(0), now);
                if (chosen == null || s < chosenScore) {
                    chosen = new ArrayList<>(candidates.subList(0, Math.min(candidates.size(), tg.batchCapacity())));
                    chosenScore = s;
                }
            } else {
                scheduleBatchCheck(tg.name() + "/" + entry.getKey(), tg.name(), oldestReady + tg.maxBatchWaitMinutes());
            }
        }
        return chosen;
    }

    /** Garante um despertador para quando o batch mais antigo estourar a espera máxima. */
    private void scheduleBatchCheck(String key, String group, double at) {
        Double pending = pendingBatchChecks.get(key);
        if (pending == null || pending < now || Math.abs(pending - at) > EPS) {
            pendingBatchChecks.put(key, at);
            schedule(at, Type.BATCH_CHECK, group);
        }
    }

    /**
     * Duração real de um processo: gama com média igual ao tempo nominal e o CV do cenário.
     * Com CV 0 devolve o nominal sem consumir o gerador aleatório (os resultados determinísticos
     * ficam idênticos). CV 1 é o exponencial.
     */
    private double sampleProcessMinutes(double nominal) {
        double cv = scenario.processTimeCv();
        if (cv <= 0) {
            return nominal;
        }
        double shape = 1.0 / (cv * cv);
        return nominal / shape * gamma(shape);
    }

    /** Gama(shape, 1) pelo método de Marsaglia &amp; Tsang (2000); para shape &lt; 1 usa o truque do reforço. */
    private double gamma(double shape) {
        if (shape < 1) {
            return gamma(shape + 1) * Math.pow(1 - rng.nextDouble(), 1.0 / shape);
        }
        double d = shape - 1.0 / 3;
        double c = 1 / Math.sqrt(9 * d);
        while (true) {
            double x;
            double v;
            do {
                x = rng.nextGaussian();
                v = 1 + c * x;
            } while (v <= 0);
            v = v * v * v;
            double u = 1 - rng.nextDouble(); // em (0, 1], evita log(0)
            if (Math.log(u) < 0.5 * x * x + d - d * v + d * Math.log(v)) {
                return d * v;
            }
        }
    }

    private void start(Machine m, List<Lot> batch) {
        queues.get(m.group()).removeAll(batch);
        Step step = batch.get(0).currentStep();
        boolean hot = false;
        for (Lot lot : batch) {
            double waited = now - lot.readyTime();
            if (waited > step.maxQueueMinutes() + EPS) {
                lot.addQueueTimeViolation();
                violations++;
            }
            lot.markProcessing();
            hot |= lot.hot();
        }
        double duration = sampleProcessMinutes(step.processMinutes());
        maxStepMinutes = Math.max(maxStepMinutes, duration);
        m.startProcessing(batch, now + duration, duration);
        operations.add(new Operation(m.id(), m.group(), now, now + duration,
                batch.stream().map(Lot::id).toList(), hot));
        schedule(m.busyUntil(), Type.STEP_END, m);
    }

    // ---------------------------------------------------------------- consultas

    public double now() {
        return now;
    }

    public DispatchRule rule() {
        return rule;
    }

    public List<Machine> machines() {
        return List.copyOf(allMachines);
    }

    public int queueLength(String group) {
        return queues.get(group).size();
    }

    /**
     * Quão perto a fila está de estourar o queue time: maior (espera / janela) entre os lotes que
     * aguardam. 0 = sem risco; 1 = no limite; acima de 1 = já estourou.
     */
    public double queueRisk(String group) {
        double risk = 0;
        for (Lot lot : queues.get(group)) {
            Step step = lot.currentStep();
            if (step.hasQueueLimit()) {
                risk = Math.max(risk, (now - lot.readyTime()) / step.maxQueueMinutes());
            }
        }
        return risk;
    }

    /**
     * Zera ciclo, atraso, violações e throughput (ex.: ao fim do aquecimento). O estado da fábrica
     * não muda; a medição recomeça deste instante. A utilização continua contando desde o início.
     */
    public void resetStatistics() {
        completed = 0;
        violations = 0;
        tardyLots = 0;
        cycleSum = 0;
        tardinessSum = 0;
        statsStart = now;
    }

    public Metrics metrics() {
        Map<String, Double> utilization = new LinkedHashMap<>();
        for (Map.Entry<String, List<Machine>> entry : machinesByGroup.entrySet()) {
            double busy = 0;
            for (Machine m : entry.getValue()) {
                busy += m.busyMinutesUntil(now);
            }
            double capacity = entry.getValue().size() * now;
            utilization.put(entry.getKey(), capacity > 0 ? busy / capacity : 0);
        }
        double days = (now - statsStart) / 1440.0;
        return new Metrics(
                now,
                released,
                completed,
                wip,
                completed > 0 ? cycleSum / completed / 60.0 : 0,
                completed > 0 ? tardinessSum / completed / 60.0 : 0,
                completed > 0 ? 100.0 * tardyLots / completed : 0,
                violations,
                days > 0 ? completed / days : 0,
                utilization);
    }

    /** Todas as operações iniciadas até agora, em ordem de início. */
    public List<Operation> operations() {
        return Collections.unmodifiableList(operations);
    }

    /**
     * Foto do estado atual. {@code wipPoints} limita os pontos recentes da série de WIP, e
     * {@code ganttWindowMinutes} define a janela (até agora) das operações que vão junto.
     */
    public Snapshot snapshot(int wipPoints, double ganttWindowMinutes) {
        List<Snapshot.MachineView> views = new ArrayList<>(allMachines.size());
        for (Machine m : allMachines) {
            boolean hot = false;
            for (Lot lot : m.lots()) {
                hot |= lot.hot();
            }
            views.add(new Snapshot.MachineView(m.id(), m.group(), m.state(), m.lots().size(), hot));
        }
        Map<String, Integer> lengths = new LinkedHashMap<>();
        Map<String, Double> risks = new LinkedHashMap<>();
        for (String group : queues.keySet()) {
            lengths.put(group, queues.get(group).size());
            risks.put(group, queueRisk(group));
        }
        int from = Math.max(0, wipSeries.size() - wipPoints);
        List<double[]> recent = new ArrayList<>(wipSeries.subList(from, wipSeries.size()));

        double windowStart = now - ganttWindowMinutes;
        List<Operation> recentOps = new ArrayList<>();
        for (int i = operations.size() - 1; i >= 0; i--) {
            Operation op = operations.get(i);
            if (op.start() < windowStart - maxStepMinutes) {
                break; // operações são anexadas em ordem de início, as anteriores já saíram da janela
            }
            if (op.end() > windowStart) {
                recentOps.add(op);
            }
        }
        Collections.reverse(recentOps);
        return new Snapshot(now, metrics(), views, lengths, risks, recent, recentOps);
    }
}
