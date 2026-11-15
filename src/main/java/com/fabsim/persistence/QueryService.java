package com.fabsim.persistence;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Executa consultas livres em modo somente leitura. A garantia de "só consulta" vem do banco (transação
 * READ ONLY); a validação do texto é uma segunda barreira que pega erros comuns e dá mensagens claras.
 * Para isolamento total, conecte com um usuário que só tenha SELECT.
 */
public final class QueryService {

    public static final int MAX_ROWS = 1000;
    private static final int TIMEOUT_SECONDS = 30;
    private static final Set<String> ALLOWED_START = Set.of("SELECT", "WITH", "VALUES", "TABLE", "EXPLAIN", "SHOW");
    private static final Pattern DOLLAR_TAG = Pattern.compile("\\$([A-Za-z_][A-Za-z0-9_]*)?\\$");

    /** Valores: Number, String ou null (qualquer outro tipo vira texto). */
    public record Result(List<String> columns, List<List<Object>> rows, boolean truncated, long millis) {

        public boolean isNumeric(int column) {
            boolean any = false;
            for (List<Object> row : rows) {
                Object value = row.get(column);
                if (value == null) {
                    continue;
                }
                if (!(value instanceof Number)) {
                    return false;
                }
                any = true;
            }
            return any;
        }
    }

    private final RunRepository repository;
    private volatile Statement current;

    public QueryService(RunRepository repository) {
        this.repository = repository;
    }

    public Result run(String sql) throws SQLException {
        check(sql);
        long start = System.nanoTime();
        try (Connection connection = repository.openConnection()) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(TIMEOUT_SECONDS);
                statement.setMaxRows(MAX_ROWS + 1);
                current = statement;
                if (!statement.execute(sql)) {
                    throw new SQLException("A consulta não devolveu linhas.");
                }
                try (ResultSet rs = statement.getResultSet()) {
                    return read(rs, start);
                }
            } finally {
                current = null;
                connection.rollback();
            }
        }
    }

    /** Pede ao banco para interromper a consulta em andamento (se houver). */
    public void cancel() {
        Statement statement = current;
        if (statement != null) {
            try {
                statement.cancel();
            } catch (SQLException ignored) {
                // já terminou ou a conexão caiu; nada a fazer
            }
        }
    }

    private static Result read(ResultSet rs, long startNanos) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int columnCount = meta.getColumnCount();
        List<String> columns = new ArrayList<>();
        for (int i = 1; i <= columnCount; i++) {
            columns.add(meta.getColumnLabel(i));
        }
        List<List<Object>> rows = new ArrayList<>();
        boolean truncated = false;
        while (rs.next()) {
            if (rows.size() == MAX_ROWS) {
                truncated = true;
                break;
            }
            List<Object> row = new ArrayList<>(columnCount);
            for (int i = 1; i <= columnCount; i++) {
                Object value = rs.getObject(i);
                row.add(value == null || value instanceof Number ? value : String.valueOf(value));
            }
            rows.add(row);
        }
        return new Result(columns, rows, truncated, (System.nanoTime() - startNanos) / 1_000_000);
    }

    /**
     * Rejeita o que não é uma única consulta: ponto e vírgula no meio (que permitiria encadear comandos,
     * inclusive um COMMIT que sairia da transação somente leitura) ou comando que não comece por
     * SELECT/WITH/VALUES/TABLE/EXPLAIN/SHOW. Ignora strings, comentários e dollar-quoting ao procurar.
     */
    public static void check(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("Digite uma consulta.");
        }
        String code = stripLiteralsAndComments(sql).strip();
        while (code.endsWith(";")) {
            code = code.substring(0, code.length() - 1).stripTrailing();
        }
        if (code.isEmpty()) {
            throw new IllegalArgumentException("Digite uma consulta.");
        }
        if (code.indexOf(';') >= 0) {
            throw new IllegalArgumentException("Só uma consulta por vez (há um ';' no meio do texto).");
        }
        Matcher first = Pattern.compile("^[A-Za-z]+").matcher(code);
        if (!first.find() || !ALLOWED_START.contains(first.group().toUpperCase())) {
            throw new IllegalArgumentException(
                    "Só são aceitas consultas: o texto deve começar por SELECT, WITH, VALUES, TABLE, EXPLAIN ou SHOW.");
        }
    }

    /** Devolve só o "código" do SQL: comentários e conteúdo de strings/identificadores entre aspas viram espaço. */
    static String stripLiteralsAndComments(String sql) {
        StringBuilder code = new StringBuilder();
        int n = sql.length();
        int i = 0;
        while (i < n) {
            char c = sql.charAt(i);
            char next = i + 1 < n ? sql.charAt(i + 1) : '\0';
            if (c == '-' && next == '-') {
                while (i < n && sql.charAt(i) != '\n') {
                    i++;
                }
                code.append(' ');
            } else if (c == '/' && next == '*') {
                int depth = 1;
                i += 2;
                while (i < n && depth > 0) {
                    if (sql.startsWith("/*", i)) {
                        depth++;
                        i += 2;
                    } else if (sql.startsWith("*/", i)) {
                        depth--;
                        i += 2;
                    } else {
                        i++;
                    }
                }
                code.append(' ');
            } else if (c == '\'') {
                boolean backslashEscapes = i > 0 && (sql.charAt(i - 1) == 'E' || sql.charAt(i - 1) == 'e')
                        && (i < 2 || !isIdentifierChar(sql.charAt(i - 2)));
                i++;
                while (i < n) {
                    char d = sql.charAt(i);
                    if (backslashEscapes && d == '\\') {
                        i += 2;
                    } else if (d == '\'') {
                        if (i + 1 < n && sql.charAt(i + 1) == '\'') {
                            i += 2;
                        } else {
                            i++;
                            break;
                        }
                    } else {
                        i++;
                    }
                }
                code.append(" ");
            } else if (c == '"') {
                i++;
                while (i < n) {
                    if (sql.charAt(i) == '"') {
                        if (i + 1 < n && sql.charAt(i + 1) == '"') {
                            i += 2;
                        } else {
                            i++;
                            break;
                        }
                    } else {
                        i++;
                    }
                }
                code.append(" x ");
            } else if (c == '$') {
                Matcher tag = DOLLAR_TAG.matcher(sql).region(i, n);
                if (tag.lookingAt()) {
                    String delimiter = tag.group();
                    int end = sql.indexOf(delimiter, i + delimiter.length());
                    i = end < 0 ? n : end + delimiter.length();
                    code.append(" ");
                } else {
                    code.append(c);
                    i++;
                }
            } else {
                code.append(c);
                i++;
            }
        }
        return code.toString();
    }

    private static boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }
}