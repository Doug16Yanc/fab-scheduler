# Fab Scheduler

Simulador de **wafer lot scheduling** em Java, com visualização 3D em JavaFX. Uma fábrica de
semicondutores simplificada, mas com os ingredientes que tornam o problema difícil de verdade:

- **Rota re-entrante**: o mesmo lote volta aos mesmos equipamentos a cada camada (6 camadas, 30 etapas).
- **Equipamentos em batch** (limpeza e deposição): só dá para juntar lotes da mesma receita, e é
  preciso decidir entre esperar o batch encher e rodar parcial.
- **Queue time**: depois da limpeza e depois da litografia há uma janela máxima de espera. Estourou, o lote
  conta como violação (na prática seria retrabalho ou sucata). As camadas iniciais têm janelas mais apertadas.
- **Falhas de equipamento** (MTBF/MTTR) na litografia e na corrosão.
- **Hot lots**: 10% dos lotes têm prazo bem mais curto.

## Como rodar

Requisitos: JDK 21+ e Maven.

```bash
# Interface 3D
mvn javafx:run

# Comparação das regras de despacho, sem interface (8 sementes, 120 dias simulados, em paralelo)
mvn compile exec:java -Dexec.mainClass=com.fabsim.Headless -Dexec.args="8 120"
```

Na tela: escolha a regra de despacho, ajuste a velocidade e clique em Iniciar. Arraste com o mouse
para girar a câmera e use a roda para o zoom. Embaixo ficam o **Gantt por máquina** (últimas 24 h
simuladas; dourado = hot lot) e a curva de WIP.

## Persistência em PostgreSQL 

Com a variável `FAB_DB_URL` definida, o `Headless` grava cada execução (resumo em `sim_run` e todas
as operações em `sim_operation`) e cria as tabelas sozinho (`src/main/resources/schema.sql`).

## Configuração do banco de dados

O Fab Scheduler grava as execuções em um PostgreSQL. A conexão é lida de variáveis de ambiente ou de um arquivo `.env` na raiz do projeto.

1. Crie o banco (uma vez):

   ```bash
   createdb -U postgres fab
   ```

2. Copie o modelo de configuração:

   ```bash
   cp .env.example .env
   ```

3. Edite o `.env` com os seus dados:

   ```bash
   FAB_DB_URL=jdbc:postgresql://localhost:5432/fab?reWriteBatchedInserts=true
   FAB_DB_USER=postgres
   FAB_DB_PASSWORD=sua_senha
   ```

| Variável          | Descrição                                                                   |
|-------------------|-----------------------------------------------------------------------------|
| `FAB_DB_URL`      | URL JDBC do banco. `reWriteBatchedInserts=true` acelera inserções em lote.  |
| `FAB_DB_USER`     | Usuário do banco.                                                           |
| `FAB_DB_PASSWORD` | Senha do usuário.                                                           |

> **Atenção:** o `.env` contém credenciais e **nunca deve ser enviado ao GitHub**.
> Confirme que ele está no `.gitignore`:
>
> ```bash
> echo ".env" >> .gitignore
> ```
>
> Se já foi commitado por engano, remova-o do índice com `git rm --cached .env` e troque a senha, pois ela continua no histórico.

Sem essas variáveis, a janela de consultas SQL e o salvamento de execuções mostram o aviso "Banco não configurado".

Depois dá para analisar direto no SQL. O arquivo [`analysis.sql`](src/main/resources/database/analysis.sql) traz consultas prontas:

| Consulta | Pergunta que responde |
|---|---|
| 1. Eficácia × eficiência por regra | Qual regra entrega melhor prazo, e a que custo de throughput e ciclo? Inclui o desvio entre sementes |
| 2. Comparação pareada contra o FIFO | A diferença se mantém semente a semente? (mesma semente = mesmas chegadas e quebras) |
| 3. Utilização por máquina e regra | Onde está o gargalo e quem fica ocioso? |
| 4. Balanceamento dentro do grupo | Alguma máquina trabalha muito mais que as irmãs? |
| 5. Parcela do tempo em hot lots | Quanto a priorização custa em tempo de máquina? |
| 6. Carga por dia | Há picos de carga ao longo da execução? |
| 7. Intervalos ociosos | Onde estão as quebras e as faltas de lote? |

Exemplo (consulta 1):

```sql
SELECT rule, count(*) AS execucoes,
       round(avg(throughput_per_day)::numeric, 2)     AS lotes_dia,
       round(avg(avg_tardiness_hours)::numeric, 1)    AS atraso_h,
       round(stddev(avg_tardiness_hours)::numeric, 1) AS desvio_atraso_h,
       round(avg(queue_time_violations)::numeric, 1)  AS violacoes_qt
FROM sim_run
WHERE horizon_days = 120
GROUP BY rule
ORDER BY atraso_h;
```

No app, o botão **Consultas SQL…** abre uma janela para rodar essas consultas (ou as suas) e ver o resultado
em tabela e em gráfico de barras, linhas ou dispersão. Ctrl+Enter executa. É **somente leitura**: a conexão
roda numa transação `READ ONLY` (o banco recusa qualquer escrita ou DDL), só uma instrução por vez é aceita,
o resultado é limitado a 1.000 linhas e há timeout de 30 s. Para isolamento total, conecte com um usuário
que só tenha `SELECT`. Para o gráfico, monte a consulta com uma coluna de rótulo/X e colunas numéricas.

O `Headless` imprime a mesma média no terminal, com o desvio do atraso (σ) entre as sementes. O que fica no
banco é o resumo de cada execução e todas as operações; o WIP ao longo do tempo e o ciclo por lote não
são salvos, e os experimentos de WIP × cv saem só em CSV.

## Vídeo de demonstração 

https://github.com/user-attachments/assets/dd318c32-9efa-46a7-bd9e-b965664abcc7

## Estrutura

```
com.fabsim
├── domain   Step, ToolGroup, Route, Lot, Machine  (modelo puro, sem JavaFX)
├── engine   SimulationEngine (eventos discretos), DispatchRules, Scenario, Metrics, Snapshot
├── persistence  RunRepository, QueryService (JDBC puro, PostgreSQL; consultas só de leitura)
├── ui       FabApp, FabScene3D, GanttView, SimulationRunner, ExperimentWindow, QueryWindow  (JavaFX; o motor roda em thread própria)
├── Headless comparação de regras em linha de comando
├── experiment/WipExperiment  cálculo do experimento (CONWIP × variabilidade) + curvas teóricas
└── WipSweep  versão de terminal do experimento (tabelas + CSV)
```

O motor não depende de JavaFX e se comunica com a tela só por `Snapshot`s imutáveis, então dá para
trocar a interface, testar o motor isolado ou rodar milhares de execuções em paralelo.

## Regras de despacho

Quando uma máquina fica livre, o lote com **menor score** na fila daquele grupo é atendido.
Criar uma regra nova é adicionar uma constante em `DispatchRules`.

| Regra | Score |
|---|---|
| FIFO | instante em que o lote entrou na fila da etapa |
| EDD | prazo final do lote |
| Critical Ratio | (prazo − agora) / processo restante |
| Folga de queue time + FIFO | tempo restante até estourar a janela da etapa; etapas sem janela seguem FIFO |

## Resultado de referência

Cenário demo, média de 8 sementes × 120 dias, sem período de aquecimento. Ciclo e atraso consideram
só os lotes concluídos.

| Regra | Ciclo (h) | Atraso (h) | % atrasados | Violações de QT | Lotes/dia |
|---|---|---|---|---|---|
| FIFO | 44,8 | 2,1 | 15,3 | 100,4 | 13,51 |
| EDD | 37,2 | 0,1 | 1,6 | 149,8 | 13,38 |
| Critical Ratio | 46,1 | 0,1 | 2,0 | 121,8 | 13,51 |
| Folga de queue time + FIFO | 44,7 | 2,3 | 15,1 | 55,5 | 13,66 |

A leitura interessante: as regras orientadas a prazo (EDD, CR) quase zeram os atrasos, mas ignoram as
janelas de queue time e geram mais violações. A regra de folga reduz as violações em cerca de 45%
em relação ao FIFO, ao custo de não melhorar o prazo. Nenhuma ganha em tudo, e é aí que mora o problema.

## Experimento: curva de Factory Physics (WIP × throughput × tempo de ciclo)

O motor aceita liberação **CONWIP** (`new SimulationEngine(cenario, regra, semente, wip)`): começa com
`wip` lotes e cada lote concluído libera outro, mantendo o WIP constante. Os tempos de processo também
podem variar: seguem uma distribuição gama com média igual ao tempo nominal e coeficiente de variação
`cv` (`Scenario.withProcessTimeCv`; 0 = fixo, 1 = exponencial). O `WipSweep` varre níveis de WIP e de
`cv` em paralelo, descarta 30 dias de aquecimento e compara com as curvas teóricas de Hopp & Spearman.

No app desktop, o botão **Experimentos…** do painel lateral abre uma janela separada com dois gráficos
(throughput × WIP e tempo de ciclo × WIP): uma linha medida por `cv`, mais o melhor caso (tracejado) e o
PWC (pontilhado) sobrepostos. Digite os valores de `cv` (ex.: `0 0.5 1 2`, ponto decimal) e clique em
*Executar experimento*; roda em paralelo em segundo plano, com barra de progresso.

Pela linha de comando:

```bash
mvn compile exec:java -Dexec.mainClass=com.fabsim.WipSweep -Dexec.args="6 180 0,0.5,1,2"   # sementes, dias, cvs
```

Gera as tabelas no terminal e o arquivo `wip-sweep.csv` (uma linha por cv × WIP, para plotar).
Resultado de referência (6 sementes × 180 dias, FIFO): gargalo teórico LITHO a 14,8 lotes/dia,
T0 = 22 h, WIP crítico ≈ 13,6.

| WIP | TH cv=0 | TH cv=1 | TH cv=2 | Ciclo cv=0 (h) | Ciclo cv=1 (h) | Ciclo cv=2 (h) |
|---|---|---|---|---|---|---|
| 10 | 8,01 | 7,98 | 7,69 | 29,9 | 30,1 | 31,2 |
| 20 | 14,53 | 12,42 | 10,89 | 33,0 | 38,6 | 44,1 |
| 32 | 14,80 | 14,09 | 12,37 | 51,9 | 54,5 | 62,1 |
| 60 | 14,84 | 14,73 | 13,84 | 96,6 | 97,9 | 104,0 |

- **Joelho** (menor WIP com 95% da capacidade do gargalo): 20 lotes com cv=0, 24 com cv=0,5, 32 com cv=1;
  com cv=2 nem WIP 60 chega lá. O platô é o mesmo (a capacidade do gargalo); a variabilidade custa
  WIP e tempo de ciclo para chegar nele.
- Depois do joelho o ciclo cresce linearmente, ≈ WIP ÷ capacidade do gargalo (lei de Little). A lei confere com
  o ciclo medido em menos de 1% no caso determinístico, o que serve de teste de sanidade do motor.
- Com pouco WIP o ciclo (≈ 31 h) fica acima de T0 (22 h) porque cada batch espera o timeout para fechar.
  Com cv &gt; 0 ele fica um pouco menor (≈ 29 h), possivelmente porque a variabilidade desfaz o sincronismo dos lotes
  e os batches enchem mais cedo (hipótese, não verificada).
- O melhor caso é um limite teórico; o PWC não. Ele assume uma linha balanceada de estações iguais, e aqui o
  gargalo é bem mais lento que as outras estações e há várias máquinas por grupo, então a curva fica acima do
  PWC mesmo com cv=1.

## Simplificações

- Falha de equipamento ocupado só vale quando o processo termina (não interrompe o lote no meio).
- Tempos de processo determinísticos, sem setup entre receitas.
- Sem controle de liberação de lotes (WIP cap) nem manutenção preventiva.

## Próximos passos

- Histograma de tempo de espera por etapa e registro das paradas de máquina no Gantt.
- Ler execuções antigas do banco e reproduzi-las na interface.
- Testes unitários do motor (invariantes: nenhum lote em duas máquinas, WIP = liberados − concluídos).
- Gating de liberação (não iniciar a limpeza se a deposição estiver congestionada) para atacar a causa das violações.
- Otimização com OR-Tools (CP-SAT) para comparar com as regras heurísticas.
