package com.fabsim.domain;

/**
 * Grupo de equipamentos intercambiáveis (ex.: 3 litografias).
 *
 * @param batchCapacity       1 = processa lote a lote; &gt; 1 = processa vários lotes juntos (forno, banho)
 * @param maxBatchWaitMinutes quanto esperar para completar um batch antes de rodar parcial
 * @param mtbfMinutes         tempo médio entre falhas (0 = nunca falha)
 * @param mttrMinutes         tempo médio de reparo
 */
public record ToolGroup(String name, int machineCount, int batchCapacity,
                        double maxBatchWaitMinutes, double mtbfMinutes, double mttrMinutes) {

    public boolean isBatch() {
        return batchCapacity > 1;
    }

    public boolean canFail() {
        return mtbfMinutes > 0;
    }
}
