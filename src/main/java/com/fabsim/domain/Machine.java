package com.fabsim.domain;

import java.util.List;

/** Um equipamento físico. Em grupos batch, processa vários lotes de uma vez. */
public final class Machine {

    public enum State { IDLE, BUSY, DOWN }

    private final String id;
    private final String group;

    private State state = State.IDLE;
    private List<Lot> lots = List.of();
    private double busyUntil;
    private double busyMinutes;
    private boolean pendingFailure;

    public Machine(String id, String group) {
        this.id = id;
        this.group = group;
    }

    public String id() { return id; }
    public String group() { return group; }
    public State state() { return state; }
    public List<Lot> lots() { return lots; }
    public double busyUntil() { return busyUntil; }
    public boolean pendingFailure() { return pendingFailure; }

    public void startProcessing(List<Lot> batch, double until, double duration) {
        this.lots = batch;
        this.busyUntil = until;
        this.busyMinutes += duration;
        this.state = State.BUSY;
    }

    /** Libera os lotes processados e deixa a máquina ociosa. */
    public List<Lot> finishProcessing() {
        List<Lot> done = lots;
        lots = List.of();
        state = State.IDLE;
        return done;
    }

    public void setDown() { state = State.DOWN; }
    public void setIdle() { state = State.IDLE; }
    public void setPendingFailure(boolean pending) { this.pendingFailure = pending; }

    /** Minutos ocupados até {@code now} (desconta o que ainda falta do processo em curso). */
    public double busyMinutesUntil(double now) {
        double inFlight = state == State.BUSY ? Math.max(0, busyUntil - now) : 0;
        return busyMinutes - inFlight;
    }
}
