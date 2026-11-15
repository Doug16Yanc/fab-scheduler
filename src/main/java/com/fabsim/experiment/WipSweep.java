package com.fabsim;

import com.fabsim.engine.Scenario;
import com.fabsim.experiment.WipExperiment;
import com.fabsim.experiment.WipExperiment.Point;
import com.fabsim.experiment.WipExperiment.Result;
import com.fabsim.experiment.WipExperiment.Theory;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.IntToDoubleFunction;
import java.util.function.ToDoubleFunction;

/**
 * Versão de terminal do experimento da curva de Factory Physics (a versão gráfica fica na janela
 * "Experimentos" do desktop). Uso: {@code WipSweep [sementes] [dias] [cvs separados por vírgula]}.
 *
 * <p>O que se espera ver: o throughput sobe com o WIP até bater no gargalo e satura; o tempo de ciclo,
 * que antes ficava perto do tempo de processo puro, passa a crescer linearmente (lei de Little).
 * Quanto maior a variabilidade, mais WIP é preciso para saturar o gargalo.
 */
public final class WipSweep {

    private static final List<Double> DEFAULT_CVS = List.of(0.0, 0.5, 1.0, 2.0);
    private static final Path CSV = Path.of("wip-sweep.csv");

    private WipSweep() {
    }

    public static void main(String[] args) throws Exception {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        int seeds = args.length > 0 ? Integer.parseInt(args[0]) : 6;
        double days = args.length > 1 ? Double.parseDouble(args[1]) : 180;
        List<Double> cvs = args.length > 2
                ? Arrays.stream(args[2].split(",")).map(Double::parseDouble).toList()
                : DEFAULT_CVS;

        Result result = WipExperiment.run(Scenario.demo(), cvs, WipExperiment.DEFAULT_WIP_LEVELS, seeds, days, null);
        Theory theory = result.theory();

        out.printf("CONWIP, regra FIFO: %d sementes × %.0f dias por ponto (%.0f de aquecimento descartados)%n%n",
                seeds, days, WipExperiment.WARMUP_DAYS);
        out.printf("Gargalo teórico: %s a %.1f lotes/dia (batch cheio, já descontadas as quebras)%n",
                theory.bottleneckGroup(), theory.bottleneckLotsPerDay());
        out.printf("T0 (processo puro) = %.1f h | WIP crítico W0 = rb × T0 = %.1f lotes%n",
                theory.t0Hours(), theory.criticalWip());
        out.printf("Obs.: com poucos lotes, cada batch espera o timeout para fechar; T0 efetivo ≈ %.1f h.%n",
                theory.t0Hours() + theory.batchWaitHours());
        out.printf("cv = coeficiente de variação do tempo de processo (0 = fixo, 1 = exponencial).%n");

        printTable(out, "Throughput (lotes/dia)", result, Point::throughputPerDay,
                theory::throughputBest, theory::throughputPwc);
        printTable(out, "Tempo de ciclo (h)", result, Point::cycleHours, theory::cycleBest, theory::cyclePwc);
        printKnees(out, result);

        writeCsv(result);
        out.printf("%nTabela gravada em %s%n", CSV.toAbsolutePath());
    }

    /** Uma coluna por nível de variabilidade, mais as referências teóricas. */
    private static void printTable(PrintStream out, String title, Result result, ToDoubleFunction<Point> value,
                                   IntToDoubleFunction best, IntToDoubleFunction pwc) {
        StringBuilder header = new StringBuilder(String.format("%5s", "WIP"));
        for (double cv : result.cvs()) {
            header.append(String.format(" %9s", "cv=" + cvLabel(cv)));
        }
        header.append(String.format(" | %9s %9s", "melhor", "PWC"));

        out.printf("%n%s%n%s%n%s%n", title, header, "-".repeat(header.length()));
        for (int i = 0; i < result.wipLevels().size(); i++) {
            int wip = result.wipLevels().get(i);
            StringBuilder row = new StringBuilder(String.format("%5d", wip));
            for (double cv : result.cvs()) {
                row.append(String.format(" %9.2f", value.applyAsDouble(result.points().get(cv).get(i))));
            }
            row.append(String.format(" | %9.2f %9.2f", best.applyAsDouble(wip), pwc.applyAsDouble(wip)));
            out.println(row);
        }
    }

    /** Por nível de variabilidade: quanto WIP é preciso para chegar perto da capacidade do gargalo. */
    private static void printKnees(PrintStream out, Result result) {
        double rb = result.theory().bottleneckLotsPerDay();
        List<Integer> levels = result.wipLevels();
        out.printf("%nJoelho: menor WIP com pelo menos %.0f%% da capacidade do gargalo (%.1f lotes/dia)%n",
                WipExperiment.KNEE_FRACTION * 100, WipExperiment.KNEE_FRACTION * rb);
        for (double cv : result.cvs()) {
            Optional<Point> knee = result.knee(cv);
            if (knee.isEmpty()) {
                out.printf("  cv=%-4s não chega lá com WIP até %d%n", cvLabel(cv), levels.get(levels.size() - 1));
            } else {
                Point p = knee.get();
                out.printf("  cv=%-4s WIP = %2d  (throughput %.2f lotes/dia, ciclo %.1f h)%n",
                        cvLabel(cv), p.wip(), p.throughputPerDay(), p.cycleHours());
            }
        }
    }

    private static void writeCsv(Result result) throws IOException {
        Theory t = result.theory();
        List<String> lines = new ArrayList<>();
        lines.add("cv,wip,throughput_lotes_dia,cycle_time_h,little_h,th_melhor_caso,ct_melhor_caso,th_pwc,ct_pwc");
        for (double cv : result.cvs()) {
            for (Point p : result.points().get(cv)) {
                lines.add(String.format(Locale.ROOT, "%s,%d,%.4f,%.3f,%.3f,%.4f,%.3f,%.4f,%.3f",
                        cvLabel(cv), p.wip(), p.throughputPerDay(), p.cycleHours(),
                        p.wip() / p.throughputPerDay() * 24,
                        t.throughputBest(p.wip()), t.cycleBest(p.wip()),
                        t.throughputPwc(p.wip()), t.cyclePwc(p.wip())));
            }
        }
        Files.write(CSV, lines, StandardCharsets.UTF_8);
    }

    private static String cvLabel(double cv) {
        return String.format(Locale.ROOT, "%.1f", cv);
    }
}
