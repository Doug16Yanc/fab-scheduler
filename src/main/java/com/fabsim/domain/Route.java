package com.fabsim.domain;

import java.util.List;

/** Sequência de etapas que todo lote percorre. Rotas re-entrantes repetem grupos de equipamentos. */
public final class Route {

    private final String name;
    private final List<Step> steps;
    private final double[] remaining; // remaining[i] = soma dos tempos de processo das etapas i..fim

    public Route(String name, List<Step> steps) {
        this.name = name;
        this.steps = List.copyOf(steps);
        this.remaining = new double[steps.size() + 1];
        for (int i = steps.size() - 1; i >= 0; i--) {
            remaining[i] = remaining[i + 1] + steps.get(i).processMinutes();
        }
    }

    public String name() {
        return name;
    }

    public List<Step> steps() {
        return steps;
    }

    public double totalProcessMinutes() {
        return remaining[0];
    }

    public double remainingProcessMinutes(int fromStepIndex) {
        return remaining[fromStepIndex];
    }
}
