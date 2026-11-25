package ro.utcn.ssatr.tracker;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Ceasul folosit la replay: "acum" este momentul înregistrat al ultimului mesaj
 * redat, plus timpul real scurs de atunci înmulțit cu viteza de redare.
 * Astfel vechimea pozițiilor și ETA ies ca la rularea originală.
 */
public final class CeasReplay extends Clock {

    private final double viteza;
    // scrise de threadul de replay, citite de tracker: perechea se schimbă atomic (synchronized)
    private long inregistratMs;   // momentul înregistrat al ultimului mesaj redat
    private long realMs;          // momentul real în care a fost redat

    public CeasReplay(double viteza) {
        this.viteza = viteza;
    }

    /** Apelat de replay la fiecare mesaj redat. */
    synchronized void seteaza(long momentInregistratMs) {
        realMs = System.currentTimeMillis();
        inregistratMs = momentInregistratMs;
    }

    @Override
    public synchronized Instant instant() {
        if (inregistratMs == 0) return Instant.now();  // înainte de primul mesaj
        return Instant.ofEpochMilli(inregistratMs + (long) ((System.currentTimeMillis() - realMs) * viteza));
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("ceasul de replay e doar în UTC");
    }
}
