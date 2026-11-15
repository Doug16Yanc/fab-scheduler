package com.fabsim.ui;

import com.fabsim.domain.ToolGroup;
import com.fabsim.engine.DispatchRules;
import com.fabsim.engine.Metrics;
import com.fabsim.engine.Snapshot;
import com.fabsim.persistence.RunRepository;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.Slider;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Tela principal: cena 3D no centro, controles e indicadores à direita, Gantt e WIP embaixo. */
public class FabApp extends Application {

    private static final String STYLESHEET = "/ui/dark-theme.css";
    private static final double SCENE_HEIGHT = 380; // altura da cena 3D (px)
    private static final double SIDE_WIDTH = 400;   // largura do painel lateral (px)

    private final SimulationRunner runner = new SimulationRunner();
    private final XYChart.Series<Number, Number> wipSeries = new XYChart.Series<>();

    private final Label clock = styled(new Label(), "clock-label");
    private final Label counts = new Label();
    private final Label kpis = new Label();
    private final Label utilization = styled(new Label(), "mono-label");
    private final Label saveStatus = styled(new Label(), "muted-label");

    private FabScene3D fab;
    private GanttView gantt;
    private ExperimentWindow experimentWindow;
    private QueryWindow queryWindow;
    private long frame;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        fab = new FabScene3D(runner.toolGroups());
        Pane scenePane = new Pane(fab.subScene());
        scenePane.getStyleClass().add("scene-pane");
        fab.bindSizeTo(scenePane);

        ComboBox<DispatchRules> ruleBox = new ComboBox<>();
        ruleBox.getItems().addAll(DispatchRules.values());
        ruleBox.setValue(DispatchRules.FIFO);
        ruleBox.setMaxWidth(Double.MAX_VALUE);
        ruleBox.setOnAction(e -> runner.setRule(ruleBox.getValue()));

        Slider speed = new Slider(30, 3000, 300);
        Label speedLabel = styled(new Label(speedText(speed.getValue())), "muted-label");
        speed.valueProperty().addListener((obs, oldValue, value) -> {
            runner.setSpeed(value.doubleValue());
            speedLabel.setText(speedText(value.doubleValue()));
        });

        Button play = new Button("Iniciar");
        play.getStyleClass().add("primary");
        play.setOnAction(e -> {
            boolean running = !runner.isRunning();
            runner.setRunning(running);
            play.setText(running ? "Pausar" : "Iniciar");
        });
        Button reset = new Button("Reiniciar");
        reset.setOnAction(e -> runner.reset());
        Button save = new Button("Salvar execução");
        save.setOnAction(e -> saveRun(save));
        Button experiments = new Button("Experimentos…");
        experiments.setOnAction(e -> openExperiments(stage));
        Button queries = new Button("Consultas SQL…");
        queries.setOnAction(e -> openQueries(stage));
        saveStatus.setWrapText(true);
        saveStatus.managedProperty().bind(saveStatus.textProperty().isNotEmpty()); // some quando vazio

        Label legend = styled(new Label(
                        "Máquina: verde = processando, cinza = ociosa, vermelha = quebrada.\n"
                                + "Barra à esquerda de cada linha = fila (altura = nº de lotes; "
                                + "cor = risco de estourar o queue time).\n"
                                + "Disco dourado = hot lot. Arraste para girar, use a roda do mouse para o zoom."),
                "muted-label");
        legend.setWrapText(true);

        VBox side = new VBox(10,
                styled(new Label("Fab Scheduler"), "title-label"),
                styled(new Label("REGRA DE DESPACHO (REINICIA A SIMULAÇÃO)"), "section-label"), ruleBox,
                styled(new Label("VELOCIDADE"), "section-label"), speed, speedLabel,
                new HBox(8, play, reset, save),
                saveStatus,
                new HBox(8, experiments, queries),
                new Separator(),
                clock, counts, kpis,
                new Separator(),
                utilization,
                new Separator(),
                legend);
        side.getStyleClass().add("side-panel");
        side.setPadding(new Insets(14));
        side.setPrefWidth(SIDE_WIDTH);
        side.setMinWidth(SIDE_WIDTH);

        NumberAxis xAxis = new NumberAxis();
        xAxis.setLabel("Dia simulado");
        xAxis.setForceZeroInRange(false);
        NumberAxis yAxis = new NumberAxis();
        yAxis.setLabel("WIP (lotes)");
        LineChart<Number, Number> chart = new LineChart<>(xAxis, yAxis);
        chart.getStyleClass().add("dark-chart");
        chart.setCreateSymbols(false);
        chart.setAnimated(false);
        chart.setLegendVisible(false);
        chart.setPrefHeight(190);
        chart.getData().add(wipSeries);

        List<String> machineIds = new ArrayList<>();
        for (ToolGroup group : runner.toolGroups()) {
            for (int i = 1; i <= group.machineCount(); i++) {
                machineIds.add(group.name() + "-" + i);
            }
        }
        gantt = new GanttView(machineIds);

        // Cena 3D com altura fixa; o que sobrar da janela vai para o Gantt (linhas mais altas).
        scenePane.setPrefHeight(SCENE_HEIGHT);
        scenePane.setMinHeight(SCENE_HEIGHT);
        scenePane.setMaxHeight(SCENE_HEIGHT);
        VBox.setVgrow(gantt, Priority.ALWAYS);

        // O painel lateral ocupa a altura inteira da janela, então nada fica cortado.
        BorderPane root = new BorderPane();
        root.setCenter(new VBox(scenePane, gantt, chart));
        root.setRight(side);

        Scene scene = new Scene(root, 1280, 800);
        scene.getStylesheets().add(Objects.requireNonNull(
                        getClass().getResource(STYLESHEET), STYLESHEET + " não encontrado em src/main/resources")
                .toExternalForm());

        stage.setTitle("Fab Scheduler");
        stage.setScene(scene);
        stage.setOnCloseRequest(e -> runner.shutdown());
        stage.show();

        new AnimationTimer() {
            @Override
            public void handle(long nowNanos) {
                Snapshot snapshot = runner.snapshot();
                fab.update(snapshot);
                updatePanel(snapshot);
                if (++frame % 15 == 0) {
                    updateChart(snapshot);
                    gantt.update(snapshot, SimulationRunner.GANTT_WINDOW_MINUTES);
                }
            }
        }.start();
    }

    @Override
    public void stop() {
        runner.shutdown();
    }

    private void updatePanel(Snapshot snapshot) {
        Metrics m = snapshot.metrics();
        long total = (long) snapshot.now();
        clock.setText(String.format("Dia %d  %02d:%02d", total / 1440, (total % 1440) / 60, total % 60));
        counts.setText(String.format("Liberados: %d   Concluídos: %d   WIP: %d",
                m.released(), m.completed(), m.wip()));
        kpis.setText(String.format(
                "Ciclo médio: %.1f h%nAtraso médio: %.1f h (%.1f%% dos lotes)%n"
                        + "Violações de queue time: %d%nThroughput: %.2f lotes/dia",
                m.avgCycleHours(), m.avgTardinessHours(), m.tardyPercent(),
                m.queueTimeViolations(), m.throughputPerDay()));

        StringBuilder sb = new StringBuilder("Utilização\n");
        m.utilization().forEach((group, value) ->
                sb.append(String.format("%-11s %3.0f%%%n", group, value * 100)));
        utilization.setText(sb.toString());
    }

    private void updateChart(Snapshot snapshot) {
        List<XYChart.Data<Number, Number>> points = new ArrayList<>();
        for (double[] p : snapshot.wip()) {
            points.add(new XYChart.Data<>(p[0] / 1440.0, p[1]));
        }
        wipSeries.getData().setAll(points);
    }

    /** Grava a execução atual no PostgreSQL, fora da thread da tela, e mostra o resultado no painel. */
    private void saveRun(Button button) {
        SimulationRunner.RunData data = runner.capture();
        if (data.operations().isEmpty()) {
            showStatus("Nada para salvar ainda: inicie a simulação primeiro.", "error-label");
            return;
        }
        button.setDisable(true);
        showStatus("Salvando…", null);

        Task<Long> task = new Task<Long>() {
            @Override
            protected Long call() throws Exception {
                RunRepository repository = RunRepository.fromEnv();
                if (repository == null) {
                    throw new IllegalStateException("Banco não configurado: defina FAB_DB_URL no ambiente "
                            + "ou em um .env (diretório atual: " + System.getProperty("user.dir") + ")");
                }
                repository.initSchema();
                return repository.save(data.rule(), data.seed(), data.horizonDays(),
                        data.metrics(), data.operations());
            }
        };
        task.setOnSucceeded(e -> {
            showStatus(String.format("Salvo: execução #%d (%d operações, %.1f dias simulados).",
                    task.getValue(), data.operations().size(), data.horizonDays()), "success-label");
            button.setDisable(false);
        });
        task.setOnFailed(e -> {
            showStatus("Erro ao salvar: " + describe(task.getException()), "error-label");
            button.setDisable(false);
        });

        Thread thread = new Thread(task, "fab-save");
        thread.setDaemon(true);
        thread.start();
    }

    private void showStatus(String text, String styleClass) {
        saveStatus.getStyleClass().removeAll("error-label", "success-label");
        if (styleClass != null) {
            saveStatus.getStyleClass().add(styleClass);
        }
        saveStatus.setText(text);
    }

    /** Mensagem da causa raiz, que costuma ser a mais útil (driver ausente, senha errada, banco inexistente...). */
    private static String describe(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }

    /** Abre (ou traz para a frente) a janela do experimento da curva de Factory Physics. */
    private void openExperiments(Stage owner) {
        if (experimentWindow == null) {
            experimentWindow = new ExperimentWindow(owner);
        }
        experimentWindow.show();
    }

    /** Abre (ou traz para a frente) a janela de consultas SQL somente leitura. */
    private void openQueries(Stage owner) {
        if (queryWindow == null) {
            queryWindow = new QueryWindow(owner);
        }
        queryWindow.show();
    }

    private static Label styled(Label label, String styleClass) {
        label.getStyleClass().add(styleClass);
        return label;
    }

    private static String speedText(double minutesPerSecond) {
        return String.format("%.0f min simulados por segundo", minutesPerSecond);
    }
}