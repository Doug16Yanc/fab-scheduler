package com.fabsim.persistence;

import com.fabsim.engine.DispatchRule;
import com.fabsim.engine.Metrics;
import com.fabsim.engine.Operation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Properties;

/** Grava execuções (resumo + operações) em PostgreSQL via JDBC puro. Thread-safe: abre uma conexão por chamada. */
public final class RunRepository {

    private static final int BATCH_SIZE = 2000;

    private final String url;
    private final String user;
    private final String password;

    public RunRepository(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
    }

    /** Cria o repositório a partir de FAB_DB_URL, FAB_DB_USER e FAB_DB_PASSWORD.
     *  Procura primeiro nas variáveis de ambiente do sistema.
     *  Se não encontrar, tenta carregar do arquivo .env (na pasta do projeto).
     *  Retorna null se FAB_DB_URL não existir em nenhum dos dois lugares.
     */
    public static RunRepository fromEnv() {
        // 1. Tenta variáveis de ambiente do sistema (prioridade)
        String url = System.getenv("FAB_DB_URL");
        String user = System.getenv("FAB_DB_USER");
        String password = System.getenv("FAB_DB_PASSWORD");

        // 2. Se não tiver URL no ambiente, tenta o arquivo .env
        if (url == null || url.isBlank()) {
            try {
                io.github.cdimascio.dotenv.Dotenv dotenv = io.github.cdimascio.dotenv.Dotenv
                        .configure()
                        .ignoreIfMissing()   // não quebra se o .env não existir
                        .load();

                url = dotenv.get("FAB_DB_URL");
                user = dotenv.get("FAB_DB_USER");
                password = dotenv.get("FAB_DB_PASSWORD");
            } catch (Exception e) {
                // se der qualquer problema no .env, continua sem banco
                return null;
            }
        }

        if (url == null || url.isBlank()) {
            return null;
        }

        return new RunRepository(url, user, password);
    }

    public void initSchema() throws SQLException {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute(loadSchema());
        }
    }

    /** Grava a execução inteira numa transação e devolve o id gerado. */
    public long save(DispatchRule rule, long seed, double horizonDays, Metrics metrics,
                     List<Operation> operations) throws SQLException {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                long runId = insertRun(connection, rule, seed, horizonDays, metrics);
                insertOperations(connection, runId, operations);
                connection.commit();
                return runId;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    private long insertRun(Connection connection, DispatchRule rule, long seed, double horizonDays,
                           Metrics m) throws SQLException {
        String sql = "INSERT INTO sim_run (rule, seed, horizon_days, released, completed, avg_cycle_hours, "
                + "avg_tardiness_hours, tardy_percent, queue_time_violations, throughput_per_day) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, rule.label());
            ps.setLong(2, seed);
            ps.setDouble(3, horizonDays);
            ps.setInt(4, m.released());
            ps.setInt(5, m.completed());
            ps.setDouble(6, m.avgCycleHours());
            ps.setDouble(7, m.avgTardinessHours());
            ps.setDouble(8, m.tardyPercent());
            ps.setInt(9, m.queueTimeViolations());
            ps.setDouble(10, m.throughputPerDay());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private void insertOperations(Connection connection, long runId, List<Operation> operations)
            throws SQLException {
        String sql = "INSERT INTO sim_operation (run_id, machine_id, tool_group, start_min, end_min, lot_ids, hot) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int pending = 0;
            for (Operation op : operations) {
                ps.setLong(1, runId);
                ps.setString(2, op.machineId());
                ps.setString(3, op.group());
                ps.setDouble(4, op.start());
                ps.setDouble(5, op.end());
                ps.setArray(6, connection.createArrayOf("integer", op.lotIds().toArray(Integer[]::new)));
                ps.setBoolean(7, op.hot());
                ps.addBatch();
                if (++pending % BATCH_SIZE == 0) {
                    ps.executeBatch();
                }
            }
            ps.executeBatch();
        }
    }

    private Connection open() throws SQLException {
        Properties props = new Properties();
        if (user != null) {
            props.setProperty("user", user);
        }
        if (password != null) {
            props.setProperty("password", password);
        }
        return DriverManager.getConnection(url, props);
    }

    private static String loadSchema() {
        try (InputStream in = RunRepository.class.getResourceAsStream("/database/schema.sql")) {
            if (in == null) {
                throw new IllegalStateException("schema.sql não encontrado no classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Falha ao ler schema.sql", e);
        }
    }

    public Connection openConnection() throws SQLException {
        return open();
    }
}
