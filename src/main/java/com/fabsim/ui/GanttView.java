package com.fabsim.ui;

import com.fabsim.engine.Operation;
import com.fabsim.engine.Snapshot;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Gantt por máquina das últimas horas simuladas: uma linha por equipamento, uma barra por operação. */
public final class GanttView extends Pane {

    private static final double LABEL_WIDTH = 100;
    private static final Color[] GROUP_COLORS = {
            Color.web("#38bdf8"), Color.web("#a78bfa"), Color.web("#34d399"),
            Color.web("#fb923c"), Color.web("#f472b6"), Color.web("#94a3b8")};
    private static final Color HOT_COLOR = Color.web("#facc15");

    private final Canvas canvas = new Canvas();
    private final List<String> machineIds;
    private final Map<String, Integer> rowByMachine = new HashMap<>();
    private final Map<String, Integer> colorByGroup = new HashMap<>();

    public GanttView(List<String> machineIds) {
        this.machineIds = machineIds;
        for (int i = 0; i < machineIds.size(); i++) {
            rowByMachine.put(machineIds.get(i), i);
        }
        getChildren().add(canvas);
        canvas.widthProperty().bind(widthProperty());
        canvas.heightProperty().bind(heightProperty());
        setPrefHeight(230);
    }

    public void update(Snapshot snapshot, double windowMinutes) {
        double width = canvas.getWidth();
        double height = canvas.getHeight();
        if (width <= LABEL_WIDTH || height <= 20 || machineIds.isEmpty()) {
            return;
        }
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.setFill(Color.web("#0B1120"));
        g.fillRect(0, 0, width, height);

        double rowHeight = (height - 16) / machineIds.size();
        double chartWidth = width - LABEL_WIDTH - 6;
        double start = snapshot.now() - windowMinutes;

        g.setFont(Font.font("System", 10));
        for (int i = 0; i < machineIds.size(); i++) {
            double y = 2 + i * rowHeight;
            g.setFill(i % 2 == 0 ? Color.web("#151F32") : Color.web("#0B1120"));
            g.fillRect(LABEL_WIDTH, y, chartWidth + 6, rowHeight);
            g.setFill(Color.web("#cbd5e1"));
            g.fillText(machineIds.get(i), 6, y + rowHeight * 0.68);
        }

        for (Operation op : snapshot.operations()) {
            Integer row = rowByMachine.get(op.machineId());
            if (row == null) {
                continue;
            }
            double x1 = LABEL_WIDTH + (Math.max(op.start(), start) - start) / windowMinutes * chartWidth;
            double x2 = LABEL_WIDTH + (Math.min(op.end(), snapshot.now()) - start) / windowMinutes * chartWidth;
            g.setFill(op.hot() ? HOT_COLOR : colorFor(op.group()));
            g.fillRect(x1, 2 + row * rowHeight + 1, Math.max(1, x2 - x1 - 0.5), rowHeight - 2);
        }

        g.setFill(Color.web("#94a3b8"));
        g.fillText(String.format("últimas %.0f h simuladas (dourado = hot lot)", windowMinutes / 60),
                LABEL_WIDTH, height - 4);
    }

    private Color colorFor(String group) {
        int index = colorByGroup.computeIfAbsent(group, k -> colorByGroup.size());
        return GROUP_COLORS[index % GROUP_COLORS.length];
    }
}
