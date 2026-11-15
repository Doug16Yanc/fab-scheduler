package com.fabsim.engine;

import java.util.List;

/** Um processamento concluído ou em andamento: que lotes ocuparam que máquina, e quando (minutos). */
public record Operation(String machineId, String group, double start, double end,
                        List<Integer> lotIds, boolean hot) {
}
