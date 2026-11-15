-- Consultas de análise sobre sim_run / sim_operation (PostgreSQL).
-- Dica: rode o Headless com sementes e horizonte fixos (ex.: "8 120") para as execuções serem comparáveis;
-- execuções do botão "Salvar execução" têm horizonte diferente e entram em outro grupo nos filtros abaixo.

-- 1) Eficácia x eficiência por regra: média e dispersão entre sementes
SELECT rule,
       count(*)                                        AS execucoes,
       round(avg(throughput_per_day)::numeric, 2)      AS lotes_dia,
       round(avg(avg_cycle_hours)::numeric, 1)         AS ciclo_h,
       round(avg(avg_tardiness_hours)::numeric, 1)     AS atraso_h,
       round(stddev(avg_tardiness_hours)::numeric, 1)  AS desvio_atraso_h,
       round(avg(tardy_percent)::numeric, 1)           AS pct_atrasados,
       round(avg(queue_time_violations)::numeric, 1)   AS violacoes_qt
FROM sim_run
WHERE horizon_days = 120
GROUP BY rule
ORDER BY atraso_h;

-- 2) Comparação pareada contra o FIFO (mesma semente = mesma sequência de chegadas e quebras),
--    o que elimina boa parte do ruído entre execuções. Negativo = melhor que o FIFO.
SELECT r.rule,
       round(avg(r.avg_tardiness_hours - f.avg_tardiness_hours)::numeric, 2)   AS delta_atraso_h,
       round(avg(r.queue_time_violations - f.queue_time_violations)::numeric, 1) AS delta_violacoes_qt,
       round(avg(r.throughput_per_day - f.throughput_per_day)::numeric, 2)     AS delta_lotes_dia,
       count(*)                                                                AS pares
FROM sim_run r
         JOIN sim_run f ON f.seed = r.seed AND f.horizon_days = r.horizon_days
    AND f.rule = 'FIFO (ordem de chegada)'
WHERE r.rule <> f.rule AND r.horizon_days = 120
GROUP BY r.rule
ORDER BY delta_atraso_h;

-- 3) Utilização média por máquina e regra (tempo em operação ÷ horizonte)
WITH busy AS (
    SELECT run_id, machine_id, tool_group, sum(end_min - start_min) AS busy_min
    FROM sim_operation
    GROUP BY run_id, machine_id, tool_group
)
SELECT r.rule, b.tool_group, b.machine_id,
       round(avg(b.busy_min / (r.horizon_days * 1440) * 100)::numeric, 1) AS utilizacao_pct
FROM busy b JOIN sim_run r ON r.id = b.run_id
WHERE r.horizon_days = 120
GROUP BY r.rule, b.tool_group, b.machine_id
ORDER BY r.rule, utilizacao_pct DESC;

-- 4) Balanceamento dentro de cada grupo de ferramentas (menor e maior utilização entre as máquinas)
WITH busy AS (
    SELECT run_id, machine_id, tool_group, sum(end_min - start_min) AS busy_min
    FROM sim_operation
    GROUP BY run_id, machine_id, tool_group
), util AS (
    SELECT b.tool_group, b.machine_id,
           avg(b.busy_min / (r.horizon_days * 1440) * 100) AS pct
    FROM busy b JOIN sim_run r ON r.id = b.run_id
    WHERE r.horizon_days = 120
    GROUP BY b.tool_group, b.machine_id
)
SELECT tool_group,
       round(min(pct)::numeric, 1)             AS menor_pct,
       round(max(pct)::numeric, 1)             AS maior_pct,
       round((max(pct) - min(pct))::numeric, 1) AS diferenca_pp
FROM util
GROUP BY tool_group
ORDER BY diferenca_pp DESC;

-- 5) Quanto do tempo de máquina vai para operações com hot lot, por regra
SELECT r.rule,
       round((100 * sum(CASE WHEN o.hot THEN o.end_min - o.start_min ELSE 0 END)
           / sum(o.end_min - o.start_min))::numeric, 1) AS pct_tempo_hot
FROM sim_operation o JOIN sim_run r ON r.id = o.run_id
WHERE r.horizon_days = 120
GROUP BY r.rule
ORDER BY pct_tempo_hot DESC;

-- 6) Carga por dia simulado em uma execução (troque o id): operações e horas de máquina
SELECT floor(start_min / 1440)::int                      AS dia,
    count(*)                                          AS operacoes,
       round((sum(end_min - start_min) / 60)::numeric, 1) AS horas_maquina
FROM sim_operation
WHERE run_id = 1
GROUP BY dia
ORDER BY dia;

-- 7) Intervalos ociosos por máquina em uma execução (troque o id).
--    O maior intervalo costuma ser uma quebra ou falta de lotes na fila.
SELECT machine_id,
       round(avg(gap)::numeric, 1) AS ocioso_medio_min,
       round(max(gap)::numeric, 1) AS ocioso_maximo_min
FROM (
         SELECT machine_id,
                start_min - lag(end_min) OVER (PARTITION BY machine_id ORDER BY start_min) AS gap
         FROM sim_operation
         WHERE run_id = 1
     ) g
WHERE gap IS NOT NULL
GROUP BY machine_id
ORDER BY ocioso_maximo_min DESC;