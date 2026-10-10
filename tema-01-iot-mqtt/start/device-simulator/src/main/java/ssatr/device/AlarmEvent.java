package ssatr.device;

/**
 * Evenimentul pus de taskul periodic în coada alarmEvents atunci când
 * temperatura citită depășește pragul. Pragul vine împreună cu temperatura,
 * ca alarma să descrie exact situația din momentul detectării.
 *
 * @param temperature  temperatura citită de jobul periodic
 * @param threshold    pragul valabil în momentul citirii
 * @param timestamp    System.currentTimeMillis() al citirii
 * @param arrivalNanos System.nanoTime() la sosirea evenimentului (timp monoton)
 */
public record AlarmEvent(double temperature, double threshold, long timestamp, long arrivalNanos) {
}