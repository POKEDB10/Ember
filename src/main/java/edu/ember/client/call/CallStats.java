package edu.ember.client.call;

/**
 * One immutable snapshot of a call's numbers, for the stats overlay and the stats log. A record:
 * all fields are final values, so a snapshot can be handed to any thread (a shallow copy is safe
 * because nothing inside can change).
 * @concept records, immutability, shallow vs deep copy
 */
public record CallStats(String mode, String codec, String transport, int sendKbps, int rttMs,
        int jitterBufferMs, int jitterTargetMs, int jitterMs, long concealed, long lateDropped,
        String device) {

    /** One line for the overlay and the log (no names, no message content). */
    public String line() {
        return "mode=" + mode + " codec=" + codec + " via=" + transport + " send=" + sendKbps + "kbps rtt="
                + (rttMs < 0 ? "-" : rttMs + "ms") + " buffer=" + jitterBufferMs + "/" + jitterTargetMs + "ms jitter="
                + jitterMs + "ms concealed=" + concealed + " late=" + lateDropped + " device=" + device;
    }
}
