package com.fabsim.ui;

import com.fabsim.domain.ToolGroup;
import com.fabsim.engine.Snapshot;
import javafx.scene.AmbientLight;
import javafx.scene.Group;
import javafx.scene.PerspectiveCamera;
import javafx.scene.PointLight;
import javafx.scene.SceneAntialiasing;
import javafx.scene.SubScene;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.Box;
import javafx.scene.shape.Cylinder;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Translate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Cena 3D da fábrica: uma linha por grupo de equipamentos, com a fila à esquerda e as máquinas
 * ao lado. Eixos do JavaFX: X para a direita, Y para BAIXO, Z para dentro da tela.
 */
public final class FabScene3D {

    private static final double MACHINE_SIZE = 60;
    private static final double MACHINE_HEIGHT = 30;
    private static final double GAP = 24;
    private static final double ROW_GAP = 150;
    private static final double QUEUE_SIZE = 40;
    private static final int MAX_LOTS_SHOWN = 4;

    private static final PhongMaterial MAT_IDLE = material("#64748b");
    private static final PhongMaterial MAT_BUSY = material("#22c55e");
    private static final PhongMaterial MAT_DOWN = material("#ef4444");
    private static final PhongMaterial MAT_LOT = material("#e2e8f0");
    private static final PhongMaterial MAT_HOT_LOT = material("#facc15");
    private static final PhongMaterial MAT_FLOOR = material("#151F32");
    private static final PhongMaterial MAT_QUEUE_OK = material("#22c55e");
    private static final PhongMaterial MAT_QUEUE_WARN = material("#eab308");
    private static final PhongMaterial MAT_QUEUE_HIGH = material("#f97316");
    private static final PhongMaterial MAT_QUEUE_LATE = material("#ef4444");

    private final SubScene subScene;
    private final Rotate rotateY = new Rotate(-20, Rotate.Y_AXIS);
    private final Rotate rotateX = new Rotate(-35, Rotate.X_AXIS);
    private final Translate dolly = new Translate(0, 0, -1700);

    private final Map<String, Box> machineBoxes = new HashMap<>();
    private final Map<String, List<Cylinder>> machineLots = new HashMap<>();
    private final Map<String, Box> queueBoxes = new HashMap<>();

    public FabScene3D(List<ToolGroup> groups) {
        Group world = new Group();

        int widest = groups.stream().mapToInt(ToolGroup::machineCount).max().orElse(1);
        double rowWidth = widest * (MACHINE_SIZE + GAP);
        double depth = groups.size() * ROW_GAP;

        Box floor = new Box(rowWidth + 620, 2, depth + 80);
        floor.setMaterial(MAT_FLOOR);
        floor.setTranslateY(1);
        world.getChildren().add(floor);

        for (int row = 0; row < groups.size(); row++) {
            ToolGroup group = groups.get(row);
            double z = -depth / 2 + row * ROW_GAP + ROW_GAP / 2;
            double left = -rowWidth / 2;

            Text label = new Text(group.name());
            label.setFont(Font.font("System", 22));
            label.setFill(Color.web("#e2e8f0"));
            label.setTranslateX(left - 270);
            label.setTranslateY(-8);
            label.setTranslateZ(z);
            world.getChildren().add(label);

            Box queue = new Box(QUEUE_SIZE, 4, QUEUE_SIZE);
            queue.setMaterial(MAT_QUEUE_OK);
            queue.setTranslateX(left - 60);
            queue.setTranslateY(-2);
            queue.setTranslateZ(z);
            queueBoxes.put(group.name(), queue);
            world.getChildren().add(queue);

            for (int i = 0; i < group.machineCount(); i++) {
                String id = group.name() + "-" + (i + 1);
                double x = left + i * (MACHINE_SIZE + GAP) + MACHINE_SIZE / 2;

                Box box = new Box(MACHINE_SIZE, MACHINE_HEIGHT, MACHINE_SIZE);
                box.setMaterial(MAT_IDLE);
                box.setTranslateX(x);
                box.setTranslateY(-MACHINE_HEIGHT / 2);
                box.setTranslateZ(z);
                machineBoxes.put(id, box);
                world.getChildren().add(box);

                List<Cylinder> discs = new ArrayList<>();
                for (int k = 0; k < MAX_LOTS_SHOWN; k++) {
                    Cylinder disc = new Cylinder(20, 4); // wafer: cilindro achatado
                    disc.setMaterial(MAT_LOT);
                    disc.setTranslateX(x);
                    disc.setTranslateY(-(MACHINE_HEIGHT + 3 + k * 5));
                    disc.setTranslateZ(z);
                    disc.setVisible(false);
                    discs.add(disc);
                    world.getChildren().add(disc);
                }
                machineLots.put(id, discs);
            }
        }

        AmbientLight ambient = new AmbientLight(Color.rgb(95, 95, 105));
        PointLight sun = new PointLight(Color.WHITE);
        sun.setTranslateX(-300);
        sun.setTranslateY(-900);
        sun.setTranslateZ(-700);

        Group root = new Group(world, ambient, sun);
        subScene = new SubScene(root, 800, 600, true, SceneAntialiasing.BALANCED);
        subScene.setFill(Color.web("#0B1120"));

        // Câmera orbital: a translação é aplicada primeiro, depois as rotações em torno da origem.
        PerspectiveCamera camera = new PerspectiveCamera(true);
        camera.setNearClip(1);
        camera.setFarClip(8000);
        // Fator menor = câmera mais perto (a fábrica ocupa mais do quadro). Era 2.0 com a cena mais alta.
        dolly.setZ(-Math.max(depth, rowWidth + 620) * 1.3);
        camera.getTransforms().addAll(rotateY, rotateX, dolly);
        subScene.setCamera(camera);

        installMouseControls();
    }

    public SubScene subScene() {
        return subScene;
    }

    public void bindSizeTo(Region region) {
        subScene.widthProperty().bind(region.widthProperty());
        subScene.heightProperty().bind(region.heightProperty());
    }

    /** Atualiza cores, filas e lotes a partir de uma foto do motor. Chamar na thread do JavaFX. */
    public void update(Snapshot snapshot) {
        for (Snapshot.MachineView view : snapshot.machines()) {
            machineBoxes.get(view.id()).setMaterial(switch (view.state()) {
                case IDLE -> MAT_IDLE;
                case BUSY -> MAT_BUSY;
                case DOWN -> MAT_DOWN;
            });
            List<Cylinder> discs = machineLots.get(view.id());
            for (int i = 0; i < discs.size(); i++) {
                Cylinder disc = discs.get(i);
                boolean visible = i < view.lots();
                disc.setVisible(visible);
                if (visible) {
                    disc.setMaterial(view.hasHotLot() ? MAT_HOT_LOT : MAT_LOT);
                }
            }
        }

        for (Map.Entry<String, Integer> entry : snapshot.queueLengths().entrySet()) {
            Box queue = queueBoxes.get(entry.getKey());
            double height = 4 + Math.min(entry.getValue(), 40) * 6;
            queue.setHeight(height);
            queue.setTranslateY(-height / 2);

            double risk = snapshot.queueRisk().get(entry.getKey());
            queue.setMaterial(risk >= 1 ? MAT_QUEUE_LATE
                    : risk >= 0.75 ? MAT_QUEUE_HIGH
                    : risk >= 0.5 ? MAT_QUEUE_WARN
                    : MAT_QUEUE_OK);
        }
    }

    private void installMouseControls() {
        double[] last = new double[2];
        subScene.setOnMousePressed(e -> {
            last[0] = e.getSceneX();
            last[1] = e.getSceneY();
        });
        subScene.setOnMouseDragged(e -> {
            rotateY.setAngle(rotateY.getAngle() + (e.getSceneX() - last[0]) * 0.3);
            double pitch = rotateX.getAngle() + (e.getSceneY() - last[1]) * 0.3;
            rotateX.setAngle(Math.clamp(pitch, -85, -8));
            last[0] = e.getSceneX();
            last[1] = e.getSceneY();
        });
        subScene.setOnScroll(e ->
                dolly.setZ(Math.clamp(dolly.getZ() + e.getDeltaY() * 2, -6000, -300)));
    }

    private static PhongMaterial material(String hex) {
        return new PhongMaterial(Color.web(hex));
    }
}