package com.fabsim.engine;

import com.fabsim.domain.Lot;
import com.fabsim.domain.Step;

/** Regras de despacho disponíveis. Para criar uma nova, basta adicionar uma constante. */
public enum DispatchRules implements DispatchRule {

    /** Ordem de chegada na fila daquela etapa. */
    FIFO("FIFO (ordem de chegada)") {
        @Override
        public double score(Lot lot, double now) {
            return lot.readyTime();
        }
    },

    /** Earliest Due Date: quem tem o prazo final mais próximo. */
    EDD("EDD (prazo mais próximo)") {
        @Override
        public double score(Lot lot, double now) {
            return lot.dueDate();
        }
    },

    /** Critical Ratio: tempo até o prazo dividido pelo processo restante. Menor = mais crítico. */
    CRITICAL_RATIO("Critical Ratio") {
        @Override
        public double score(Lot lot, double now) {
            return criticalRatio(lot, now);
        }
    },

    /**
     * Folga de queue time primeiro: quem está mais perto de estourar a janela de espera passa
     * na frente. Etapas sem janela seguem FIFO.
     */
    QUEUE_TIME_SLACK("Folga de queue time + FIFO") {
        @Override
        public double score(Lot lot, double now) {
            Step step = lot.currentStep();
            if (!step.hasQueueLimit()) {
                return 1e9 + lot.readyTime();
            }
            return (lot.readyTime() + step.maxQueueMinutes()) - now;
        }
    };

    private final String label;

    DispatchRules(String label) {
        this.label = label;
    }

    @Override
    public String label() {
        return label;
    }

    @Override
    public String toString() {
        return label;
    }

    private static double criticalRatio(Lot lot, double now) {
        return (lot.dueDate() - now) / Math.max(lot.remainingProcessMinutes(), 1e-9);
    }
}
