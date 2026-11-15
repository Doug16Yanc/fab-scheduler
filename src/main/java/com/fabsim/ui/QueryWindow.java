package com.fabsim.ui;

import com.fabsim.persistence.QueryService;
import com.fabsim.persistence.QueryService.Result;
import com.fabsim.persistence.RunRepository;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.ScatterChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.StringConverter;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Janela de consultas SQL: só leitura, com tabela de resultado e gráfico (barras, linhas ou dispersão).
 * Os exemplos vêm de {@code analysis.sql}. Ctrl+Enter executa.
 */
public final class QueryWindow {

    private static final String STYLESHEET = "/ui/dark-theme.css";
    private static final double SCREEN_FRACTION = 0.9;
    private static final int MAX_SERIES = 6;
    private static final int MAX_BARS = 60;
    private static final int MAX_POINTS = 1000;
    private static final String BAR = "Barras";
    private static final String LINE = "Linhas";
    private static final String SCATTER = "Dispersão";

    /** Um exemplo de consulta: título (da linha "-- N) ...") e texto. */
    private record Example(String title, String sql) {
    }

    private final Stage stage = new Stage();
    private final ComboBox<Example> examples = new ComboBox<>();
    private final TextArea editor = new TextArea("SELECT * FROM sim_run ORDER BY id DESC LIMIT 20;");
    private final Button run = new Button("Executar");
    private final Button cancel = new Button("Cancelar");
    private final Label status = styled(new Label("Só consultas (SELECT, WITH...). Ctrl+Enter executa."), "muted-label");
    private final TableView<List<Object>> table = new TableView<>();

    private final ComboBox<String> typeBox = new ComboBox<>();
    private final ComboBox<String> xBox = new ComboBox<>();
    private final ListView<String> yList = new ListView<>();
    private final Label chartNote = styled(new Label(), "muted-label");
    private final StackPane chartHolder = new StackPane();

    private Result result;
    private volatile QueryService service;
    private boolean updatingControls;

    public QueryWindow(Window owner) {
        stage.initOwner(owner);
        stage.setTitle("Consultas SQL (somente leitura)");

        examples.setPromptText("Exemplos (analysis.sql)");
        examples.getItems().addAll(loadExamples());
        examples.setConverter(new StringConverter<>() {
            @Override
            public String toString(Example example) {
                return example == null ? "" : example.title();
            }

            @Override
            public Example fromString(String text) {
                return null;
            }
        });
        examples.setOnAction(e -> {
            Example selected = examples.getValue();
            if (selected != null) {
                editor.setText(selected.sql());
            }
        });
        examples.setPrefWidth(420);

        editor.getStyleClass().add("sql-editor");
        editor.setPrefRowCount(7);
        editor.setOnKeyPressed(e -> {
            if (e.isControlDown() && e.getCode() == KeyCode.ENTER) {
                execute();
                e.consume();
            }
        });

        run.getStyleClass().add("primary");
        run.setOnAction(e -> execute());
        cancel.setDisable(true);
        cancel.setOnAction(e -> {
            if (service != null) {
                service.cancel();
            }
        });
        status.setWrapText(true);

        HBox actions = new HBox(10, run, cancel, status);
        actions.setAlignment(Pos.CENTER_LEFT);
        VBox top = new VBox(8, examples, editor, actions);
        top.setPadding(new Insets(14, 14, 8, 14));

        table.setPlaceholder(styled(new Label("Execute uma consulta para ver o resultado."), "muted-label"));
        table.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);

        SplitPane split = new SplitPane(table, buildChartPane());
        split.setOrientation(javafx.geometry.Orientation.VERTICAL);
        split.setDividerPositions(0.45);

        BorderPane root = new BorderPane();
        root.setTop(top);
        root.setCenter(split);
        BorderPane.setMargin(split, new Insets(0, 14, 14, 14));

        Rectangle2D screen = Screen.getPrimary().getVisualBounds();
        double width = Math.clamp(screen.getWidth() * SCREEN_FRACTION, 1000, screen.getWidth());
        double height = Math.clamp(screen.getHeight() * SCREEN_FRACTION, 680, screen.getHeight());

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
        stage.toFront();
    }

    // ---------- execução ----------

    private void execute() {
        if (run.isDisabled()) {
            return;
        }
        String sql = editor.getText();
        try {
            QueryService.check(sql);
        } catch (IllegalArgumentException e) {
            showStatus(e.getMessage(), "error-label");
            return;
        }

        run.setDisable(true);
        cancel.setDisable(false);
        showStatus("Executando...", null);

        Task<Result> task = new Task<>() {
            @Override
            protected Result call() throws Exception {
                RunRepository repository = RunRepository.fromEnv();
                if (repository == null) {
                    throw new IllegalStateException("Banco não configurado: defina FAB_DB_URL no ambiente "
                            + "ou em um .env (diretório atual: " + System.getProperty("user.dir") + ")");
                }
                service = new QueryService(repository);
                return service.run(sql);
            }
        };
        task.setOnSucceeded(e -> {
            finish();
            show(task.getValue());
        });
        task.setOnFailed(e -> {
            finish();
            showStatus(describe(task.getException()), "error-label");
        });
        Thread thread = new Thread(task, "fab-query");
        thread.setDaemon(true);
        thread.start();
    }

    private void finish() {
        run.setDisable(false);
        cancel.setDisable(true);
    }

    private void show(Result value) {
        result = value;
        fillTable(value);
        fillChartControls(value);
        drawChart();
        String text = String.format("%d linhas em %d ms.", value.rows().size(), value.millis());
        if (value.truncated()) {
            text += String.format(" Resultado cortado em %d linhas: use LIMIT ou filtre.", QueryService.MAX_ROWS);
        }
        showStatus(text, "success-label");
    }

    // ---------- tabela ----------

    private void fillTable(Result value) {
        table.getColumns().clear();
        for (int i = 0; i < value.columns().size(); i++) {
            final int index = i;
            TableColumn<List<Object>, String> column = new TableColumn<>(value.columns().get(i));
            column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(format(cell.getValue().get(index))));
            column.setSortable(false);
            if (value.isNumeric(i)) {
                column.setStyle("-fx-alignment: CENTER-RIGHT;");
            }
            table.getColumns().add(column);
        }
        table.getItems().setAll(value.rows());
    }

    private static String format(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                return String.valueOf(d);
            }
            return BigDecimal.valueOf(d).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.stripTrailingZeros().toPlainString();
        }
        return String.valueOf(value);
    }

    // ---------- gráfico ----------

    private Node buildChartPane() {
        typeBox.getItems().addAll(BAR, LINE, SCATTER);
        typeBox.setValue(BAR);
        yList.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        yList.setPrefHeight(70);
        yList.setPrefWidth(260);

        typeBox.setOnAction(e -> drawChart());
        xBox.setOnAction(e -> {
            if (!updatingControls) {
                drawChart();
            }
        });
        yList.getSelectionModel().getSelectedItems().addListener(
                (javafx.collections.ListChangeListener<String>) change -> {
                    if (!updatingControls) {
                        drawChart();
                    }
                });

        HBox controls = new HBox(10,
                styled(new Label("GRÁFICO"), "section-label"), typeBox,
                styled(new Label("X"), "section-label"), xBox,
                styled(new Label("Y (várias com Ctrl)"), "section-label"), yList,
                chartNote);
        controls.setAlignment(Pos.CENTER_LEFT);
        controls.setPadding(new Insets(8, 0, 8, 0));

        VBox.setVgrow(chartHolder, Priority.ALWAYS);
        chartHolder.getChildren().add(styled(new Label("Execute uma consulta para montar o gráfico."), "muted-label"));
        return new VBox(controls, chartHolder);
    }

    /** X padrão = primeira coluna; Y padrão = colunas numéricas (até {@value #MAX_SERIES}) menos o X. */
    private void fillChartControls(Result value) {
        updatingControls = true;
        try {
            xBox.getItems().setAll(value.columns());
            xBox.getSelectionModel().select(0);
            List<String> numeric = new ArrayList<>();
            for (int i = 0; i < value.columns().size(); i++) {
                if (value.isNumeric(i)) {
                    numeric.add(value.columns().get(i));
                }
            }
            yList.getItems().setAll(numeric);
            yList.getSelectionModel().clearSelection();
            String x = value.columns().get(0);
            int picked = 0;
            for (int i = 0; i < numeric.size() && picked < MAX_SERIES; i++) {
                if (!numeric.get(i).equals(x)) {
                    yList.getSelectionModel().select(i);
                    picked++;
                }
            }
        } finally {
            updatingControls = false;
        }
    }

    private void drawChart() {
        chartNote.setText("");
        if (result == null) {
            return;
        }
        int xIndex = xBox.getSelectionModel().getSelectedIndex();
        List<Integer> yIndexes = new ArrayList<>();
        for (String name : yList.getSelectionModel().getSelectedItems()) {
            yIndexes.add(result.columns().indexOf(name));
        }
        if (yIndexes.size() > MAX_SERIES) {
            yIndexes = yIndexes.subList(0, MAX_SERIES);
            chartNote.setText("Mostrando só as " + MAX_SERIES + " primeiras séries.");
        }
        String type = typeBox.getValue();
        String problem = null;
        if (xIndex < 0 || yIndexes.isEmpty()) {
            problem = "Sem coluna numérica para o eixo Y: escolha uma ou ajuste a consulta.";
        } else if (SCATTER.equals(type) && !result.isNumeric(xIndex)) {
            problem = "Dispersão precisa de uma coluna numérica no eixo X.";
        }
        if (problem != null) {
            chartHolder.getChildren().setAll(styled(new Label(problem), "muted-label"));
            return;
        }

        XYChart<?, ?> chart = switch (type) {
            case LINE -> lineChart(xIndex, yIndexes);
            case SCATTER -> scatterChart(xIndex, yIndexes);
            default -> barChart(xIndex, yIndexes);
        };
        chart.setAnimated(false);
        chart.setLegendSide(Side.BOTTOM);
        chart.setLegendVisible(yIndexes.size() > 1);
        chart.getStyleClass().addAll("dark-chart", "query-chart");
        chartHolder.getChildren().setAll(chart);
    }

    private XYChart<String, Number> barChart(int xIndex, List<Integer> yIndexes) {
        CategoryAxis xAxis = new CategoryAxis();
        xAxis.setLabel(result.columns().get(xIndex));
        NumberAxis yAxis = new NumberAxis();
        BarChart<String, Number> chart = new BarChart<>(xAxis, yAxis);
        int limit = Math.min(result.rows().size(), MAX_BARS);
        if (result.rows().size() > limit) {
            chartNote.setText("Barras: mostrando as " + MAX_BARS + " primeiras linhas.");
        }
        for (int y : yIndexes) {
            XYChart.Series<String, Number> series = new XYChart.Series<>();
            series.setName(result.columns().get(y));
            for (int r = 0; r < limit; r++) {
                List<Object> row = result.rows().get(r);
                if (row.get(y) instanceof Number n) {
                    series.getData().add(new XYChart.Data<>(format(row.get(xIndex)), n.doubleValue()));
                }
            }
            chart.getData().add(series);
        }
        return chart;
    }

    private XYChart<?, ?> lineChart(int xIndex, List<Integer> yIndexes) {
        int limit = Math.min(result.rows().size(), MAX_POINTS);
        if (result.rows().size() > limit) {
            chartNote.setText("Mostrando os " + MAX_POINTS + " primeiros pontos.");
        }
        NumberAxis yAxis = new NumberAxis();
        if (result.isNumeric(xIndex)) {
            NumberAxis xAxis = new NumberAxis();
            xAxis.setForceZeroInRange(false);
            xAxis.setLabel(result.columns().get(xIndex));
            LineChart<Number, Number> chart = new LineChart<>(xAxis, yAxis);
            chart.setCreateSymbols(limit <= 40);
            for (int y : yIndexes) {
                chart.getData().add(numericSeries(xIndex, y, limit));
            }
            return chart;
        }
        CategoryAxis xAxis = new CategoryAxis();
        xAxis.setLabel(result.columns().get(xIndex));
        LineChart<String, Number> chart = new LineChart<>(xAxis, yAxis);
        chart.setCreateSymbols(limit <= 40);
        for (int y : yIndexes) {
            XYChart.Series<String, Number> series = new XYChart.Series<>();
            series.setName(result.columns().get(y));
            for (int r = 0; r < limit; r++) {
                List<Object> row = result.rows().get(r);
                if (row.get(y) instanceof Number n) {
                    series.getData().add(new XYChart.Data<>(format(row.get(xIndex)), n.doubleValue()));
                }
            }
            chart.getData().add(series);
        }
        return chart;
    }

    private XYChart<Number, Number> scatterChart(int xIndex, List<Integer> yIndexes) {
        int limit = Math.min(result.rows().size(), MAX_POINTS);
        if (result.rows().size() > limit) {
            chartNote.setText("Mostrando os " + MAX_POINTS + " primeiros pontos.");
        }
        NumberAxis xAxis = new NumberAxis();
        xAxis.setForceZeroInRange(false);
        xAxis.setLabel(result.columns().get(xIndex));
        NumberAxis yAxis = new NumberAxis();
        yAxis.setForceZeroInRange(false);
        ScatterChart<Number, Number> chart = new ScatterChart<>(xAxis, yAxis);
        for (int y : yIndexes) {
            chart.getData().add(numericSeries(xIndex, y, limit));
        }
        return chart;
    }

    private XYChart.Series<Number, Number> numericSeries(int xIndex, int yIndex, int limit) {
        XYChart.Series<Number, Number> series = new XYChart.Series<>();
        series.setName(result.columns().get(yIndex));
        for (int r = 0; r < limit; r++) {
            List<Object> row = result.rows().get(r);
            if (row.get(xIndex) instanceof Number x && row.get(yIndex) instanceof Number y) {
                series.getData().add(new XYChart.Data<Number, Number>(x.doubleValue(), y.doubleValue()));
            }
        }
        return series;
    }

    // ---------- exemplos (analysis.sql) ----------

    /** Divide o arquivo nos blocos que começam com "-- N) título"; o que vem antes é só cabeçalho. */
    private static List<Example> loadExamples() {
        List<Example> list = new ArrayList<>();
        try (InputStream in = QueryWindow.class.getResourceAsStream("/database/analysis.sql")) {
            if (in == null) {
                return list;
            }
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Matcher header = Pattern.compile("(?m)^-- (\\d+\\) .*)$").matcher(text);
            List<int[]> spans = new ArrayList<>();
            List<String> titles = new ArrayList<>();
            while (header.find()) {
                spans.add(new int[]{header.start(), header.end()});
                titles.add(header.group(1));
            }
            for (int i = 0; i < spans.size(); i++) {
                int end = i + 1 < spans.size() ? spans.get(i + 1)[0] : text.length();
                String body = text.substring(spans.get(i)[0], end).strip();
                list.add(new Example(titles.get(i), body));
            }
        } catch (IOException e) {
            // sem exemplos: a janela continua funcionando
        }
        return list;
    }

    // ---------- utilidades ----------

    private void showStatus(String text, String styleClass) {
        status.getStyleClass().removeAll("error-label", "success-label");
        if (styleClass != null) {
            status.getStyleClass().add(styleClass);
        }
        status.setText(text);
    }

    private static String describe(Throwable error) {
        if (error instanceof IllegalStateException || error instanceof IllegalArgumentException) {
            return error.getMessage();
        }
        Throwable root = error;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }

    private static Label styled(Label label, String styleClass) {
        label.getStyleClass().add(styleClass);
        return label;
    }
}