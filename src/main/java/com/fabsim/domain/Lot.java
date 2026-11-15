package com.fabsim.domain;

/** Um lote de wafers (tipicamente 25, o conteúdo de um FOUP) percorrendo a rota. */
public final class Lot {

    public enum State { WAITING, PROCESSING, DONE }

    public static final int WAFERS_PER_LOT = 25;

    private final int id;
    private final Route route;
    private final double releaseTime;
    private final double dueDate;
    private final boolean hot;

    private int stepIndex;
    private double readyTime;
    private State state = State.WAITING;
    private double finishTime = Double.NaN;
    private int queueTimeViolations;

    public Lot(int id, Route route, double releaseTime, double dueDate, boolean hot) {
        this.id = id;
        this.route = route;
        this.releaseTime = releaseTime;
        this.dueDate = dueDate;
        this.hot = hot;
        this.readyTime = releaseTime;
    }

    public int id() { return id; }
    public Route route() { return route; }
    public double releaseTime() { return releaseTime; }
    public double dueDate() { return dueDate; }
    public boolean hot() { return hot; }
    public int stepIndex() { return stepIndex; }
    public State state() { return state; }
    public boolean isDone() { return state == State.DONE; }
    public int queueTimeViolations() { return queueTimeViolations; }

    /** Instante em que o lote ficou pronto para a etapa atual (início da espera). */
    public double readyTime() { return readyTime; }

    public double finishTime() { return finishTime; }

    public Step currentStep() {
        return route.steps().get(stepIndex);
    }

    /** Tempo de processo que ainda falta, incluindo a etapa atual. */
    public double remainingProcessMinutes() {
        return route.remainingProcessMinutes(stepIndex);
    }

    public void markProcessing() {
        state = State.PROCESSING;
    }

    public void addQueueTimeViolation() {
        queueTimeViolations++;
    }

    /** Conclui a etapa atual: avança na rota ou finaliza o lote. */
    public void completeStep(double now) {
        stepIndex++;
        if (stepIndex >= route.steps().size()) {
            state = State.DONE;
            finishTime = now;
        } else {
            state = State.WAITING;
            readyTime = now;
        }
    }
}
