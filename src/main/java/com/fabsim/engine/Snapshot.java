package com.fabsim.engine;

import com.fabsim.domain.Machine;

import java.util.List;
import java.util.Map;

/** Foto imutável do estado da fábrica, segura para a interface ler enquanto a simulação roda. */
public record Snapshot(double now,
                       Metrics metrics,
                       List<MachineView> machines,
                       Map<String, Integer> queueLengths,
                       Map<String, Double> queueRisk,
                       List<double[]> wip,
                       List<Operation> operations) {

    public record MachineView(String id, String group, Machine.State state, int lots, boolean hasHotLot) {
    }
}
