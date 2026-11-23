package edu.ember.server;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who is calling whom. The server never sees call audio or video: it only rings, connects two
 * devices, and forwards their (end-to-end encrypted) packets. One call per user at a time.
 * Generic over the device type so it can be tested without sockets.
 * @param <D> one signed-in device (a connection)
 * @concept generics, state machine, synchronization
 */
public final class CallRegistry<D> {
    /** Ringing calls give up after this long. */
    public static final long RING_TIMEOUT_MS = 45_000;
    /** Signalling messages (SDP, ICE candidates, keys) allowed per call, so a client cannot flood. */
    public static final int MAX_SIGNALS = 400;
    /** Relayed media per call direction: ~1 Mbit/s sustained, a 2 s burst for key frames. */
    private static final double MEDIA_BYTES_PER_SEC = 128_000;

    public enum State { RINGING, ACTIVE }

    /** One call between two users and, once answered, exactly two devices. */
    public static final class Call<D> {
        public final String id;
        public final String caller;
        public final String callee;
        public final boolean video;
        /** Rings nobody: the callee is unreachable, hidden or busy. Behaves like an unanswered call. */
        public final boolean phantom;
        public final long createdMs = System.currentTimeMillis();
        private volatile State state = State.RINGING;
        private volatile D callerDevice;
        private volatile D calleeDevice;
        private int signals;
        private final TokenBucket callerMedia = new TokenBucket(MEDIA_BYTES_PER_SEC * 2, MEDIA_BYTES_PER_SEC);
        private final TokenBucket calleeMedia = new TokenBucket(MEDIA_BYTES_PER_SEC * 2, MEDIA_BYTES_PER_SEC);

        private Call(String id, String caller, D callerDevice, String callee, boolean video, boolean phantom) {
            this.id = id;
            this.phantom = phantom;
            this.caller = caller;
            this.callerDevice = callerDevice;
            this.callee = callee;
            this.video = video;
        }

        public State state() {
            return state;
        }

        public String other(String userId) {
            return userId.equals(caller) ? callee : caller;
        }

        public D callerDevice() {
            return callerDevice;
        }

        public D calleeDevice() {
            return calleeDevice;
        }
    }

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Call<D>> calls = new ConcurrentHashMap<>();
    private final Map<String, String> callOfUser = new ConcurrentHashMap<>();
    private final Map<D, Call<D>> callOfDevice = new ConcurrentHashMap<>();

    /**
     * Starts a call, or returns null when the caller is already in one. When the callee cannot be
     * rung (unreachable, or busy) the call is a phantom: the caller sees exactly what an
     * unanswered call looks like, so calling never reveals someone's presence.
     */
    public synchronized Call<D> ring(String caller, D callerDevice, String callee, boolean video,
            boolean reachable) {
        if (caller.equals(callee) || callOfUser.containsKey(caller)) {
            return null;
        }
        boolean phantom = !reachable || callOfUser.containsKey(callee);
        byte[] bytes = new byte[18];
        random.nextBytes(bytes);
        Call<D> call = new Call<>(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),
                caller, callerDevice, callee, video, phantom);
        calls.put(call.id, call);
        callOfUser.put(caller, call.id);
        if (!phantom) {
            callOfUser.put(callee, call.id);
        }
        callOfDevice.put(callerDevice, call);
        return call;
    }

    /** The callee answers on one device; returns null when the call is gone or already answered. */
    public synchronized Call<D> accept(String callId, String callee, D device) {
        Call<D> call = calls.get(callId);
        if (call == null || call.phantom || !call.callee.equals(callee) || call.state != State.RINGING) {
            return null;
        }
        call.state = State.ACTIVE;
        call.calleeDevice = device;
        callOfDevice.put(device, call);
        return call;
    }

    /** Ends a call either person is in; returns it, or null when it was not theirs or already over. */
    public synchronized Call<D> end(String callId, String userId) {
        Call<D> call = calls.get(callId);
        if (call == null || (!call.caller.equals(userId) && !call.callee.equals(userId))) {
            return null;
        }
        remove(call);
        return call;
    }

    /** Ends a call that is still ringing after the timeout; returns it, or null. */
    public synchronized Call<D> expire(String callId) {
        Call<D> call = calls.get(callId);
        if (call == null || call.state != State.RINGING) {
            return null;
        }
        remove(call);
        return call;
    }

    /** A device disconnected: ends the calls it was part of and returns them. */
    public synchronized List<Call<D>> dropDevice(D device) {
        List<Call<D>> ended = new ArrayList<>();
        Call<D> call = callOfDevice.get(device);
        if (call != null) {
            remove(call);
            ended.add(call);
        }
        return ended;
    }

    /** The answered call this device is in, or null. Lock-free: used for every media packet. */
    public Call<D> activeCallOf(D device) {
        Call<D> call = callOfDevice.get(device);
        return call != null && call.state == State.ACTIVE ? call : null;
    }

    public Call<D> find(String callId) {
        return calls.get(callId);
    }

    /** The other device of an answered call, or null when this device is not one of the two. */
    public D peerDevice(Call<D> call, D device) {
        if (call.state != State.ACTIVE) {
            return null;
        }
        if (device.equals(call.callerDevice)) {
            return call.calleeDevice;
        }
        if (device.equals(call.calleeDevice)) {
            return call.callerDevice;
        }
        return null;
    }

    /** Counts one signalling message; false once the call has used its allowance. */
    public synchronized boolean allowSignal(Call<D> call) {
        return ++call.signals <= MAX_SIGNALS;
    }

    /** Byte-rate limit on relayed media, per direction; false means drop this packet. */
    public boolean allowMedia(Call<D> call, D device, int bytes) {
        return (device.equals(call.callerDevice) ? call.callerMedia : call.calleeMedia).tryConsume(bytes);
    }

    private void remove(Call<D> call) {
        calls.remove(call.id);
        callOfUser.remove(call.caller, call.id);
        callOfUser.remove(call.callee, call.id);
        if (call.callerDevice != null) {
            callOfDevice.remove(call.callerDevice, call);
        }
        if (call.calleeDevice != null) {
            callOfDevice.remove(call.calleeDevice, call);
        }
    }
}
