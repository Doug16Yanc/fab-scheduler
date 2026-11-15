package com.fabsim.engine;

import com.fabsim.domain.Route;
import com.fabsim.domain.Step;
import com.fabsim.domain.ToolGroup;

import java.util.ArrayList;
import java.util.List;

/**
 * Parâmetros de uma fábrica simulada.
 *
 * @param meanInterArrivalMinutes intervalo médio entre liberações de lotes (chegadas exponenciais)
 * @param hotLotFraction          fração de lotes urgentes (hot lots)
 * @param dueFactor               prazo = liberação + dueFactor × tempo de processo puro
 * @param hotDueFactor            idem, para hot lots (mais apertado)
 * @param processTimeCv           coeficiente de variação dos tempos de processo (desvio ÷ média):
 *                                0 = determinístico, 1 = como um exponencial, 2 = muito variável.
 *                                A média continua sendo o tempo nominal da etapa.
 */
public record Scenario(List<ToolGroup> groups, Route route, double meanInterArrivalMinutes,
                       double hotLotFraction, double dueFactor, double hotDueFactor,
                       double processTimeCv) {

    /** Mesma fábrica, com outra variabilidade nos tempos de processo. */
    public Scenario withProcessTimeCv(double cv) {
        return new Scenario(groups, route, meanInterArrivalMinutes, hotLotFraction, dueFactor, hotDueFactor, cv);
    }

    /**
     * Fab de brinquedo, mas com os ingredientes do problema real: rota re-entrante (6 camadas),
     * equipamentos em batch (limpeza e deposição), janela de queue time depois da deposição e da
     * corrosão, falhas de equipamento e hot lots. Tempos em minutos.
     */
    public static Scenario demo() {
        List<ToolGroup> groups = List.of(
                new ToolGroup("CLEAN", 2, 4, 30, 0, 0),
                new ToolGroup("DEPOSITION", 2, 4, 180, 0, 0),
                new ToolGroup("LITHO", 3, 1, 0, 1200, 480),
                new ToolGroup("ETCH", 3, 1, 0, 4000, 180),
                new ToolGroup("METROLOGY", 2, 1, 0, 0, 0));

        List<Step> steps = new ArrayList<>();
        for (int layer = 1; layer <= 6; layer++) {
            boolean critical = layer <= 2; // camadas iniciais têm janelas mais apertadas
            steps.add(Step.of("CLEAN", "CLEAN", 30));
            steps.add(new Step("DEP", "DEPOSITION", 90, critical ? 150 : 300)); // janela: limpeza -> deposição
            steps.add(Step.of("LITHO", "LITHO", 45));
            steps.add(new Step("ETCH", "ETCH", 40, critical ? 120 : 240));      // janela: litografia -> corrosão
            steps.add(Step.of("METRO", "METROLOGY", 15));
        }
        return new Scenario(groups, new Route("CMOS-6L", steps), 105, 0.10, 3.0, 1.8, 0.0);
    }
}
