package com.fabsim.ui;

import com.fabsim.engine.Scenario;
import com.fabsim.experiment.WipExperiment;
import com.fabsim.experiment.WipExperiment.Point;
import com.fabsim.experiment.WipExperiment.Result;
import com.fabsim.experiment.WipExperiment.Theory;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Scene;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntToDoubleFunction;
import java.util.function.ToDoubleFunction;

/**
 * Janela do experimento da curva de Factory Physics: throughput e tempo de ciclo em função do WIP,
 * para vários níveis de variabilidade, com as curvas teóricas (melhor caso e PWC) sobrepostas.
 */
public final class ExperimentWindow {

    private static final String STYLESHEET = "/ui/dark-theme.css";
    private static final int SEEDS = 6;
    private static final double DAYS = 180;
    private static final int MAX_CVS = 6;
    private static final double MAX_CV = 4;
    private static final double SCREEN_FRACTION = 0.9;

    private final Stage stage = new Stage();
    private final TextField cvField = new TextField("0 0.5 1 2");
    private final Button run = new Button("Executar experimento");
    private final ProgressBar progress = new ProgressBar(0);
    private final Label status = styled(new Label(
            "Informe os valores de cv e clique em Executar (leva alguns segundos)."), "muted-label");
    private final Label summary = styled(new Label(), "mono-label");
    private final HBox charts = new HBox(12);

    public ExperimentWindow(Window owner) {
        stage.initOwner(owner);
        stage.setTitle("Experimento: curva WIP × throughput × tempo de ciclo");

        cvField.setPromptText("ex.: 0 0.5 1 2");
        cvField.setPrefColumnCount(16);
        run.getStyleClass().add("primary");
        run.setOnAction(e -> start());
        progress.setPrefWidth(180);
        progress.setVisible(false);
        status.setWrapText(true);
        summary.managedProperty().bind(summary.textProperty().isNotEmpty());

        HBox controls = new HBox(10,
                styled(new Label("VARIABILIDADE (CV)"), "section-label"), cvField, run, progress);
        controls.setAlignment(Pos.CENTER_LEFT);

        VBox top = new VBox(8, controls, status, summary);
        top.setPadding(new Insets(14));
        charts.setPadding(new Insets(0, 14, 14, 14));

        BorderPane root = new BorderPane();
        root.setTop(top);
        root.setCenter(charts);

        // Abre ocupando ~90% da área útil da tela (e continua redimensionável/maximizável).
        Rectangle2D screen = Screen.getPrimary().getVisualBounds();
        double width = Math.clamp(screen.getWidth() * SCREEN_FRACTION, 1000, screen.getWidth());
        double height = Math.clamp(screen.getHeight() * SCREEN_FRACTION, 640, screen.getHeight());

        Scene scene = new Scene(root);
        scene.getStylesheets().add(Objects.requireNonNull(
                        getClass().getResource(STYLESHEET), STYLESHEET + " não encontrado em src/main/resources")
                .toExternalForm());
        stage.setScene(scene);

        stage.setMinWidth(800);
        stage.setMinHeight(500);
        stage.setWidth(width);
        stage.setHeight(height);
        stage.setX(screen.getMinX() + (screen.getWidth() - width) / 2);
        stage.setY(screen.getMinY() + (screen.getHeight() - height) / 2);
    }

    public void show() {
        stage.show();
        stage.setMaximized(true);
        stage.toFront();
    }

    private void start() {
        List<Double> parsed;
        try {
            parsed = parseCvs(cvField.getText());
        } catch (IllegalArgumentException e) {
            showStatus(e.getMessage(), "error-label");
            return;
        }
        List<Double> cvs = parsed;

        run.setDisable(true);
        progress.setProgress(0);
        progress.setVisible(true);
        showStatus(String.format("Rodando %d simulações em paralelo...",
                cvs.size() * WipExperiment.DEFAULT_WIP_LEVELS.length * SEEDS), null);

        Task<Result> task = new Task<Result>() {
            @Override
            protected Result call() throws Exception {
                return WipExperiment.run(Scenario.demo(), cvs, WipExperiment.DEFAULT_WIP_LEVELS, SEEDS, DAYS,
                        fraction -> updateProgress(fraction, 1.0));
            }
        };
        progress.progressProperty().bind(task.progressProperty());
        task.setOnSucceeded(e -> {
            progress.progressProperty().unbind();
            progress.setVisible(false);
            render(task.getValue());
            showStatus(String.format("Pronto: %d sementes × %.0f dias por ponto.", SEEDS, DAYS), "success-label");
            run.setDisable(false);
        });
        task.setOnFailed(e -> {
            progress.progressProperty().unbind();
            progress.setVisible(false);
            showStatus("Erro no experimento: " + describe(task.getException()), "error-label");
            run.setDisable(false);
        });

        Thread thread = new Thread(task, "fab-experiment");
        thread.setDaemon(true);
        thread.start();
    }

    private void render(Result result) {
        Theory theory = result.theory();
        charts.getChildren().setAll(
                buildChart("Throughput × WIP", "Throughput (lotes/dia)", result,
                        Point::throughputPerDay, theory::throughputBest, theory::throughputPwc),
                buildChart("Tempo de ciclo × WIP", "Tempo de ciclo (h)", result,
                        Point::cycleHours, theory::cycleBest, theory::cyclePwc));

        StringBuilder text = new StringBuilder(String.format(
                "Gargalo: %s a %.1f lotes/dia   |   T0 = %.1f h   |   WIP crítico W0 = %.1f lotes%n",
                theory.bottleneckGroup(), theory.bottleneckLotsPerDay(), theory.t0Hours(), theory.criticalWip()));
        text.append(String.format("Joelho (%.0f%% do gargalo):", WipExperiment.KNEE_FRACTION * 100));
        List<Integer> levels = result.wipLevels();
        for (double cv : result.cvs()) {
            Optional<Point> knee = result.knee(cv);
            text.append("   cv=").append(cvLabel(cv)).append(" → ")
                    .append(knee.map(p -> "WIP " + p.wip()).orElse("> " + levels.get(levels.size() - 1)));
        }
        summary.setText(text.toString());
    }

    /** Curvas teóricas primeiro (tracejada e pontilhada, no CSS), depois uma linha medida por cv. */
    private static LineChart<Number, Number> buildChart(String title, String yLabel, Result result,
                                                        ToDoubleFunction<Point> value,
                                                        IntToDoubleFunction best, IntToDoubleFunction pwc) {
        List<Integer> levels = result.wipLevels();
        NumberAxis xAxis = new NumberAxis(0, levels.get(levels.size() - 1), 10);
        xAxis.setLabel("WIP (lotes em processo)");
        NumberAxis yAxis = new NumberAxis();
        yAxis.setLabel(yLabel);

        LineChart<Number, Number> chart = new LineChart<>(xAxis, yAxis);
        chart.setTitle(title);
        chart.setCreateSymbols(false);
        chart.setAnimated(false);
        chart.setLegendSide(Side.BOTTOM);
        chart.getStyleClass().addAll("dark-chart", "experiment-chart");
        HBox.setHgrow(chart, Priority.ALWAYS);

        chart.getData().add(series("Melhor caso", levels, best));
        chart.getData().add(series("PWC", levels, pwc));
        for (double cv : result.cvs()) {
            List<Point> points = result.points().get(cv);
            XYChart.Series<Number, Number> measured = new XYChart.Series<>();
            measured.setName("cv=" + cvLabel(cv));
            for (Point p : points) {
                measured.getData().add(new XYChart.Data<Number, Number>(p.wip(), value.applyAsDouble(p)));
            }
            chart.getData().add(measured);
        }
        return chart;
    }

    private static XYChart.Series<Number, Number> series(String name, List<Integer> levels, IntToDoubleFunction f) {
        XYChart.Series<Number, Number> series = new XYChart.Series<>();
        series.setName(name);
        for (int wip : levels) {
            series.getData().add(new XYChart.Data<Number, Number>(wip, f.applyAsDouble(wip)));
        }
        return series;
    }

    /** Valores separados por espaço (ou ponto e vírgula), com ponto decimal: "0 0.5 1 2". */
    private static List<Double> parseCvs(String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Informe ao menos um valor de cv.");
        }
        if (trimmed.contains(",")) {
            throw new IllegalArgumentException(
                    "Use ponto como separador decimal (0.5) e espaço entre os valores, sem vírgula.");
        }
        List<Double> cvs = new ArrayList<>();
        for (String token : trimmed.split("[\\s;]+")) {
            double cv;
            try {
                cv = Double.parseDouble(token);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Valor inválido: \"" + token + "\".");
            }
            if (cv < 0 || cv > MAX_CV) {
                throw new IllegalArgumentException(String.format("Cada cv deve estar entre 0 e %.0f.", MAX_CV));
            }
            if (!cvs.contains(cv)) {
                cvs.add(cv);
            }
        }
        if (cvs.size() > MAX_CVS) {
            throw new IllegalArgumentException("No máximo " + MAX_CVS + " valores de cv de uma vez.");
        }
        return cvs;
    }

    private void showStatus(String text, String styleClass) {
        status.getStyleClass().removeAll("error-label", "success-label");
        if (styleClass != null) {
            status.getStyleClass().add(styleClass);
        }
        status.setText(text);
    }

    private static String describe(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }

    private static String cvLabel(double cv) {
        return String.format(Locale.ROOT, "%.1f", cv);
    }

    private static Label styled(Label label, String styleClass) {
        label.getStyleClass().add(styleClass);
        return label;
    }
}