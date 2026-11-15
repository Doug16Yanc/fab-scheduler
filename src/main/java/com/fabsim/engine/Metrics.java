package com.fabsim.engine;

import java.util.Map;

/** Indicadores da simulação até o instante atual. Ciclo e atraso consideram só lotes concluídos. */
public record Metrics(double timeMinutes,
                      int released,
                      int completed,
                      int wip,
                      double avgCycleHours,
                      double avgTardinessHours,
                      double tardyPercent,
                      int queueTimeViolations,
                      double throughputPerDay,
                      Map<String, Double> utilization) {
}
