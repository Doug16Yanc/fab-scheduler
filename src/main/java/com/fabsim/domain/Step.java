package com.fabsim.domain;

/**
 * Uma etapa da rota. Roda em um grupo de equipamentos e pode ter uma janela máxima de
 * espera (queue time): o tempo entre o fim da etapa anterior e o início desta.
 */
public record Step(String name, String toolGroup, double processMinutes, double maxQueueMinutes) {

    public static final double NO_LIMIT = Double.POSITIVE_INFINITY;

    public static Step of(String name, String toolGroup, double processMinutes) {
        return new Step(name, toolGroup, processMinutes, NO_LIMIT);
    }

    public boolean hasQueueLimit() {
        return !Double.isInfinite(maxQueueMinutes);
    }
}
