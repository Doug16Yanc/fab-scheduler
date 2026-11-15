package com.fabsim.engine;

import com.fabsim.domain.Lot;

/**
 * Regra de despacho: quando uma máquina fica livre, o lote com MENOR score é atendido primeiro.
 */
public interface DispatchRule {

    String label();

    double score(Lot lot, double now);
}
