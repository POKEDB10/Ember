package edu.ember.server;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who is in which call. The server never sees call audio or video: it rings, keeps the list of
 * participants, and forwards their end-to-end encrypted packets. One-to-one and group calls use
 * the same rules; one call per person at a time; at most {@link #MAX_PARTICIPANTS} in a call.
 * Generic over the device type so it can be tested without sockets.
 * @param <D> one signed-in device (a connection)
 * @concept generics, state machine, synchronization, collections
 */
public final class CallRegistry<D> {
    /** Ringing calls give up after this long. */
    public static final long RING_TIMEOUT_MS = 45_000;
    /** Signalling messages (SDP, ICE candidates, keys) per device: a burst, then a steady trickle. */
    private static final double SIGNAL_BURST = 400;
    private static final double SIGNALS_PER_SEC = 20;
    /** More people than this would make every phone upload too many streams. */
    public static final int MAX_PARTICIPANTS = 8;
    /** Relayed media per device: ~2.5 Mbit/s sustained (720p video plus voice), a 3 s burst for key frames. */
    private static final double MEDIA_BYTES_PER_SEC = 320_000;

    public enum State { RINGING, ACTIVE }

    /** One call: the people invited, and the devices that have joined. */
    public static final class Call<D> {
        public final String id;
        public final String conversationId;
        public final String caller;
        public final boolean video;
        public final boolean group;
        /** Rings nobody: the callee is unreachable, hidden or busy. Behaves like an unanswered call. */
        public final boolean phantom;
        public final Set<String> invited;
        public final long createdMs = System.currentTimeMillis();
        private volatile State state = State.RINGING;
        private final Map<String, D> participants = new LinkedHashMap<>();
        private final Map<D, TokenBucket> media = new ConcurrentHashMap<>();
        private final Map<D, TokenBucket> signals = new ConcurrentHashMap<>();

        private Call(String id, String conversationId, String caller, D callerDevice, Set<String> invited,
                boolean video, boolean group, boolean phantom) {
            this.id = id;
            this.conversationId = conversationId;
            this.caller = caller;
            this.invited = Set.copyOf(invited);
            this.video = video;
            this.group = group;
            this.phantom = phantom;
            participants.put(caller, callerDevice);
        }

        public State state() {
            return state;
        }

        /** The one other person of a one-to-one call. */
        public String callee() {
            return invited.iterator().next();
        }

        public synchronized Map<String, D> participants() {
            return Map.copyOf(participants);
        }

        public synchronized D deviceOf(String userId) {
            return participants.get(userId);
        }
    }

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Call<D>> calls = new ConcurrentHashMap<>();
    private final Map<String, String> callOfUser = new ConcurrentHashMap<>();
    private final Map<D, Call<D>> callOfDevice = new ConcurrentHashMap<>();

    /**
     * Starts a call, or returns null when the caller is already in one. A one-to-one call whose
     * callee cannot be rung (unreachable or busy) is a phantom: the caller sees exactly what an
     * unanswered call looks like, so calling never reveals someone's presence.
     */
    public synchronized Call<D> ring(String caller, D callerDevice, String conversationId, Set<String> invited,
            boolean video, boolean reachable) {
        if (invited.isEmpty() || invited.contains(caller) || callOfUser.containsKey(caller)) {
            return null;
        }
        boolean group = conversationId.startsWith("grp:");
        boolean phantom = !group && (!reachable || callOfUser.containsKey(invited.iterator().next()));
        byte[] bytes = new byte[18];
        random.nextBytes(bytes);
        Call<D> call = new Call<>(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),
                conversationId, caller, callerDevice, invited, video, group, phantom);
        calls.put(call.id, call);
        callOfUser.put(caller, call.id);
        if (!group && !phantom) {
            callOfUser.put(call.callee(), call.id); // a ringing one-to-one callee is busy for others
        }
        callOfDevice.put(callerDevice, call);
        return call;
    }

    /** An invited person joins on one device; null when the call is over, full or not theirs. */
    public synchronized Call<D> join(String callId, String userId, D device) {
        Call<D> call = calls.get(callId);
        if (call == null || call.phantom || !call.invited.contains(userId)) {
            return null;
        }
        synchronized (call) {
            String busyWith = callOfUser.get(userId);
            if (call.participants.containsKey(userId) || (busyWith != null && !busyWith.equals(callId))
                    || call.participants.size() >= MAX_PARTICIPANTS || (!call.group && call.state == State.ACTIVE)) {
                return null;
            }
            call.participants.put(userId, device);
        }
        call.state = State.ACTIVE;
        callOfUser.put(userId, call.id);
        callOfDevice.put(device, call);
        return call;
    }

    /**
     * Someone leaves. Returns true when that ended the whole call: always for one-to-one calls,
     * and for group calls once at most one person is left (or the caller hangs up before anyone joined).
     */
    public synchronized boolean leave(Call<D> call, String userId) {
        D device;
        synchronized (call) {
            device = call.participants.remove(userId);
        }
        if (device != null) {
            callOfDevice.remove(device, call);
            call.media.remove(device);
            call.signals.remove(device);
        }
        callOfUser.remove(userId, call.id);
        boolean over = !call.group || call.participants().size() <= 1;
        if (over) {
            remove(call);
        }
        return over;
    }

    /** Ends a call that nobody answered; returns it, or null when it was answered or is gone. */
    public synchronized Call<D> expire(String callId) {
        Call<D> call = calls.get(callId);
        if (call == null || call.state != State.RINGING) {
            return null;
        }
        remove(call);
        return call;
    }

    /** The call this device is in (ringing or answered), or null. Lock-free: used for every packet. */
    public Call<D> callOf(D device) {
        return callOfDevice.get(device);
    }

    public Call<D> find(String callId) {
        return calls.get(callId);
    }

    /** The other devices of an answered call, for relaying one packet to everyone else. */
    public List<D> others(Call<D> call, D device) {
        List<D> others = new ArrayList<>();
        if (call.state != State.ACTIVE) {
            return others;
        }
        for (D participant : call.participants().values()) {
            if (!participant.equals(device)) {
                others.add(participant);
            }
        }
        return others;
    }

    /** Counts one signalling message from a device; each device has its own allowance. */
    public boolean allowSignal(Call<D> call, D device) {
        return call.signals.computeIfAbsent(device,
                ignored -> new TokenBucket(SIGNAL_BURST, SIGNALS_PER_SEC)).tryConsume();
    }

    /** A single-use, short-lived permission for one device to take over a person's place in a call. */
    private record Ticket<D>(String callId, String userId, D from, D to, long expiresMs) { }

    private final Map<String, Ticket<D>> tickets = new ConcurrentHashMap<>();

    /** What a successful hand-off did: the call and the device that lost its place. */
    public record Handoff<D>(Call<D> call, D oldDevice) { }

    /**
     * Phase 1 of a hand-off (calls v2): the person is in this answered call on another device;
     * returns a ticket for {@code newDevice}, or null when that is not the case.
     */
    public synchronized String prepareHandoff(String callId, String userId, D newDevice, long nowMs, long ttlMs) {
        tickets.values().removeIf(ticket -> ticket.expiresMs() < nowMs);
        Call<D> call = calls.get(callId);
        if (call == null || call.state != State.ACTIVE || call.group) {
            return null;
        }
        D current = call.deviceOf(userId);
        if (current == null || current.equals(newDevice)) {
            return null;
        }
        byte[] bytes = new byte[18];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tickets.put(token, new Ticket<>(callId, userId, current, newDevice, nowMs + ttlMs));
        return token;
    }

    /**
     * Phase 2: swaps the device in one step. The ticket is used up by its own device either way; it fails when it
     * expired, belongs to another device, or the person's place already moved (the first commit wins).
     */
    public synchronized Handoff<D> commitHandoff(String token, String callId, D newDevice, long nowMs) {
        Ticket<D> ticket = tickets.get(token);
        if (ticket == null || !ticket.to().equals(newDevice) || !ticket.callId().equals(callId)) {
            return null; // another device can neither use nor cancel someone's ticket
        }
        tickets.remove(token);
        if (ticket.expiresMs() < nowMs) {
            return null;
        }
        Call<D> call = calls.get(ticket.callId());
        if (call == null || call.state != State.ACTIVE) {
            return null;
        }
        synchronized (call) {
            if (!ticket.from().equals(call.participants.get(ticket.userId()))) {
                return null;
            }
            call.participants.put(ticket.userId(), newDevice);
        }
        callOfDevice.remove(ticket.from(), call);
        callOfDevice.put(newDevice, call);
        call.media.remove(ticket.from());
        call.signals.remove(ticket.from());
        return new Handoff<>(call, ticket.from());
    }

    /** The call a person is in or being rung for, or null. */
    public Call<D> callOfUser(String userId) {
        String id = callOfUser.get(userId);
        return id == null ? null : calls.get(id);
    }

    /** Byte-rate limit on relayed media, per sending device; false means drop this packet. */
    public boolean allowMedia(Call<D> call, D device, int bytes) {
        return call.media.computeIfAbsent(device,
                ignored -> new TokenBucket(MEDIA_BYTES_PER_SEC * 3, MEDIA_BYTES_PER_SEC)).tryConsume(bytes);
    }

    private void remove(Call<D> call) {
        calls.remove(call.id);
        callOfUser.remove(call.caller, call.id);
        for (String invitee : call.invited) {
            callOfUser.remove(invitee, call.id);
        }
        for (D device : call.participants().values()) {
            callOfDevice.remove(device, call);
        }
        callOfDevice.values().removeIf(other -> other == call);
    }
}
