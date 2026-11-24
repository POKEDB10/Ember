package edu.ember.client;

import edu.ember.common.security.E2eCrypto;
import edu.ember.common.util.MiniJson;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;

/**
 * A voice call in the desktop app, one-to-one or group, compatible with the website's call.js.
 * Java has no WebRTC, so the desktop always uses the encrypted relay: it agrees a key with each
 * person (ECDH P-256, HKDF), hands each of them its own sender key over that encrypted channel,
 * and sends G.711 mu-law voice (8 kHz, 20 ms packets) encrypted with AES-GCM. The server copies the
 * packets to the others without being able to read them. Incoming voices are mixed and played.
 * @concept threads, producer-consumer, cryptography, javax.sound
 */
public final class CallSession {
    public enum State { RINGING_OUT, RINGING_IN, CONNECTING, ACTIVE, ENDED }

    /** Wideband voice: 16 kHz captures speech up to 8 kHz, twice what a phone line carries. */
    private static final AudioFormat VOICE = new AudioFormat(16000f, 16, 1, true, false);
    private static final int FRAME_SAMPLES = 320; // 20 ms at 16 kHz
    private static final int MAX_QUEUED_FRAMES = 8; // 160 ms per voice before old audio is dropped
    private static final byte AUDIO = 1;
    private static final byte MULAW = 2;
    private static final byte ADPCM = 3;
    private static final int[] IMA_STEP = {7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37,
        41, 45, 50, 55, 60, 66, 73, 80, 88, 97, 107, 118, 130, 143, 157, 173, 190, 209, 230, 253, 279, 307, 337,
        371, 408, 449, 494, 544, 598, 658, 724, 796, 876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066,
        2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442,
        11487, 12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767};
    private static final int[] IMA_INDEX = {-1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8};
    /** The encoder's step index carries over between frames; each frame still decodes on its own. */
    private final int[] adpcmIndex = new int[1];
    private static final byte PING = 3;
    private static final byte PONG = 4;

    /** One other person in the call. */
    private static final class Peer {
        final String user;
        final KeyPair pair;
        final byte[] myPub;
        volatile byte[] pairKey;
        volatile String code = "";
        int sigSend;
        int sigRecv = -1;
        volatile byte[] senderKey;
        volatile String tag;
        volatile boolean adpcm;
        volatile long lastCounter = -1;
        final ConcurrentLinkedDeque<short[]> voice = new ConcurrentLinkedDeque<>();

        Peer(String user, KeyPair pair, byte[] myPub) {
            this.user = user;
            this.pair = pair;
            this.myPub = myPub;
        }
    }

    private final String me;
    private final BiConsumer<String, Map<String, String>> sendCall;
    private final Consumer<byte[]> sendMedia;
    private final Runnable changed;
    private final Map<String, Peer> peers = new ConcurrentHashMap<>();
    private final Map<String, Peer> byTag = new ConcurrentHashMap<>();
    public final String conversationId;
    public final String title;
    private volatile String callId = "";
    private volatile State state;
    private volatile String status = "";
    private volatile boolean muted;
    private volatile long startedMs;
    private volatile int pingMs = -1;
    private volatile byte[] senderKey;
    private volatile byte[] senderTag;
    private long sendCounter;
    private volatile TargetDataLine microphone;
    private volatile SourceDataLine speaker;
    private Thread captureThread;
    private Thread mixerThread;
    /** Calls v2 voice pipeline, or null for the legacy path (flag off, or a group call). */
    private final edu.ember.client.call.VoiceEngine engine;
    private final edu.ember.client.call.QualityPolicy policy;
    private int framesSincePing;

    CallSession(String me, String conversationId, String title, boolean incoming, String callId,
            BiConsumer<String, Map<String, String>> sendCall, Consumer<byte[]> sendMedia, Runnable changed) {
        this(me, conversationId, title, incoming, callId, sendCall, sendMedia, changed, null, null, null);
    }

    /**
     * With calls v2 (1:1 only): the {@link edu.ember.client.call.VoiceEngine} pipeline replaces the
     * legacy capture/mixer threads; everything else (keys, signals, relay packets) is shared.
     */
    CallSession(String me, String conversationId, String title, boolean incoming, String callId,
            BiConsumer<String, Map<String, String>> sendCall, Consumer<byte[]> sendMedia, Runnable changed,
            edu.ember.client.call.CallSettings settings, edu.ember.client.call.AudioDevices.Provider devices,
            edu.ember.client.call.CallStatsListener statsLog) {
        this.me = me;
        this.conversationId = conversationId;
        this.title = title;
        this.callId = callId == null ? "" : callId;
        this.sendCall = sendCall;
        this.sendMedia = sendMedia;
        this.changed = changed;
        state = incoming ? State.RINGING_IN : State.RINGING_OUT;
        status = incoming ? "incoming call" : "calling…";
        rotateSenderKey();
        if (settings != null && conversationId.startsWith("dm:")) {
            policy = settings.mode().policy();
            engine = new edu.ember.client.call.VoiceEngine(devices, settings, edu.ember.client.call.Clock.SYSTEM,
                    (sequence, codecId, frame) -> {
                        int now = (int) (System.nanoTime() / 1_000_000);
                        if (++framesSincePing >= 100) { // every 2 s: measures round-trip time for the stats
                            framesSincePing = 0;
                            sendPacket(PING, (byte) 0, new byte[0], now);
                        }
                        sendPacket(AUDIO, (byte) codecId, frame, now);
                    },
                    message -> {
                        status = message;
                        changed.run();
                    });
            if (statsLog != null) {
                engine.addStatsListener(statsLog);
            }
            engine.addStatsListener(stats -> lastStats = stats);
        } else {
            policy = null;
            engine = null;
        }
    }

    private volatile edu.ember.client.call.CallStats lastStats;

    /** Latest stats snapshot (calls v2 only), or null. */
    public edu.ember.client.call.CallStats stats() {
        return lastStats;
    }

    /** True when this call runs the calls v2 voice pipeline. */
    public boolean v2() {
        return engine != null;
    }

    public State state() { return state; }
    public String status() { return status; }
    public String callId() { return callId; }
    public boolean muted() { return muted; }
    public int pingMs() { return pingMs; }
    public long startedMs() { return startedMs; }

    /** Each person and the safety code shared with them. */
    public synchronized Map<String, String> codes() {
        Map<String, String> codes = new java.util.TreeMap<>();
        peers.values().forEach(peer -> codes.put(peer.user, peer.code));
        return codes;
    }

    public void setMuted(boolean value) {
        muted = value;
        if (engine != null) {
            engine.setMuted(value);
        }
        changed.run();
    }

    // ---- events from the server (called in order on the controller's frame thread) ----

    synchronized void ringing(String id) {
        callId = id;
        changed.run();
    }

    /** The server's list of people already in the call, after we answered. */
    synchronized void roster(Map<String, String> fields) {
        int count = Integer.parseInt(fields.getOrDefault("n", "0"));
        for (int index = 0; index < count; index++) {
            addPeer(fields.get("user." + index));
        }
        state = State.CONNECTING;
        status = "connecting…";
        changed.run();
    }

    synchronized void onEvent(Map<String, String> fields) {
        String kind = fields.getOrDefault("kind", "");
        switch (kind) {
            case "end" -> end(reason(fields.get("reason")));
            case "joined" -> {
                addPeer(fields.get("userId"));
                if (state == State.RINGING_OUT) {
                    state = State.CONNECTING;
                    status = "connecting…";
                }
            }
            case "left" -> {
                Peer gone = peers.remove(fields.get("userId"));
                if (engine != null) {
                    engine.removePeer(fields.get("userId"));
                }
                if (gone != null && gone.tag != null) {
                    byTag.remove(gone.tag);
                }
                rotateSenderKey(); // the person who left can no longer read what follows
                peers.values().forEach(this::sendRelayInfo);
                status = fields.get("userId") + " left";
            }
            // Keys and audio only after this side answered, and only from people the server said are in the call:
            // nothing can switch the microphone on while the call is still ringing here.
            case "key" -> {
                if (answered() && peers.containsKey(fields.get("from"))) {
                    onKey(fields.get("from"), fields.getOrDefault("data", ""));
                }
            }
            case "relay" -> {
                if (answered() && peers.containsKey(fields.get("from"))) {
                    onRelay(fields.get("from"), fields.getOrDefault("data", ""));
                }
            }
            default -> { } // "sdp" and "ice" are for browsers' direct links; the desktop always relays
        }
        changed.run();
    }

    private boolean answered() {
        return state == State.CONNECTING || state == State.ACTIVE;
    }

    private static String reason(String reason) {
        return switch (reason == null ? "" : reason) {
            case "declined" -> "call declined";
            case "noanswer" -> "no answer";
            case "missed" -> "missed call";
            case "elsewhere" -> "answered on another device";
            default -> "call ended";
        };
    }

    // ---- keys: one per pair of people, then a sender key handed to each ----

    private Peer addPeer(String user) {
        if (user == null || user.equals(me)) {
            return null;
        }
        return peers.computeIfAbsent(user, ignored -> {
            try {
                KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
                generator.initialize(new ECGenParameterSpec("secp256r1"));
                KeyPair pair = generator.generateKeyPair();
                byte[] raw = E2eCrypto.rawPublicKey((ECPublicKey) pair.getPublic());
                sendCall.accept("key", Map.of("callId", callId, "to", user, "data", E2eCrypto.encode(raw)));
                return new Peer(user, pair, raw);
            } catch (GeneralSecurityException exception) {
                throw new IllegalStateException("No P-256 support", exception);
            }
        });
    }

    private void onKey(String from, String data) {
        Peer peer = peers.get(from);
        if (peer == null || peer.pairKey != null) {
            return; // one key per pair
        }
        try {
            byte[] theirs = E2eCrypto.decode(data);
            byte[] secret = E2eCrypto.ecdh(peer.pair.getPrivate(), theirs);
            String[] pair = {me, from};
            Arrays.sort(pair);
            byte[] salt = (callId + "|" + pair[0] + "|" + pair[1]).getBytes(StandardCharsets.UTF_8);
            peer.pairKey = E2eCrypto.hkdf(secret, salt, "ember-call-v2".getBytes(StandardCharsets.UTF_8), 16);
            peer.code = safetyCode(peer.myPub, theirs);
            sendRelayInfo(peer);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            status = "could not secure the call with " + from;
        }
    }

    /** The same 12 digits the website shows for this pair. */
    static String safetyCode(byte[] mine, byte[] theirs) throws GeneralSecurityException {
        byte[][] sorted = {mine, theirs};
        Arrays.sort(sorted, (left, right) -> E2eCrypto.encode(left).compareTo(E2eCrypto.encode(right)));
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        sha.update(sorted[0]);
        byte[] hash = sha.digest(sorted[1]);
        StringBuilder digits = new StringBuilder();
        for (int index = 0; index < 6; index++) {
            digits.append(String.format("%02d", (hash[index] & 0xFF) % 100));
        }
        return digits.substring(0, 4) + " " + digits.substring(4, 8) + " " + digits.substring(8);
    }

    /** Pair signals: the person with the smaller name uses direction 2, the other 3. */
    private int direction(Peer peer, boolean mine) {
        return (me.compareTo(peer.user) < 0) == mine ? 2 : 3;
    }

    private void sendSignal(Peer peer, String kind, String json) throws GeneralSecurityException {
        byte[] head = new byte[5];
        head[0] = (byte) direction(peer, true);
        ByteBuffer.wrap(head, 1, 4).putInt(++peer.sigSend);
        byte[] iv = Arrays.copyOf(head, 12);
        byte[] sealed = E2eCrypto.aesGcm(true, peer.pairKey, iv, head, json.getBytes(StandardCharsets.UTF_8));
        byte[] packet = new byte[5 + sealed.length];
        System.arraycopy(head, 0, packet, 0, 5);
        System.arraycopy(sealed, 0, packet, 5, sealed.length);
        sendCall.accept(kind, Map.of("callId", callId, "to", peer.user, "data", E2eCrypto.encode(packet)));
    }

    private String openSignal(Peer peer, String data) throws GeneralSecurityException {
        byte[] bytes = E2eCrypto.decode(data);
        if (bytes.length < 21 || bytes[0] != direction(peer, false)) {
            throw new GeneralSecurityException("Bad signal");
        }
        int counter = ByteBuffer.wrap(bytes, 1, 4).getInt();
        if (counter <= peer.sigRecv) {
            throw new GeneralSecurityException("Replayed signal");
        }
        byte[] head = Arrays.copyOf(bytes, 5);
        byte[] plain = E2eCrypto.aesGcm(false, peer.pairKey, Arrays.copyOf(head, 12), head,
                Arrays.copyOfRange(bytes, 5, bytes.length));
        peer.sigRecv = counter;
        return new String(plain, StandardCharsets.UTF_8);
    }

    /** Our sender key and what we can decode: voice in mu-law only, no video. */
    private void sendRelayInfo(Peer peer) {
        if (peer.pairKey == null) {
            return;
        }
        try {
            sendSignal(peer, "relay", "{\"key\":" + MiniJson.quote(E2eCrypto.encode(senderKey)) + ",\"tag\":"
                    + MiniJson.quote(E2eCrypto.encode(senderTag)) + ",\"opus\":false,\"adpcm\":true,\"video\":[]"
                    + (engine == null ? "" : ",\"v\":2,\"codecs\":[4,3,2]") + "}");
        } catch (GeneralSecurityException exception) {
            status = "could not secure the call";
        }
    }

    private void onRelay(String from, String data) {
        Peer peer = peers.get(from);
        if (peer == null || peer.pairKey == null) {
            return;
        }
        try {
            Object message = MiniJson.parse(openSignal(peer, data));
            if (MiniJson.path(message, "keyframe") != null) {
                return; // video only
            }
            Object key = MiniJson.path(message, "key");
            Object tag = MiniJson.path(message, "tag");
            if (key instanceof String keyText && tag instanceof String tagText
                    && E2eCrypto.decode(keyText).length == 16 && E2eCrypto.decode(tagText).length == 4
                    && (!byTag.containsKey(tagText) || byTag.get(tagText) == peer)) {
                if (peer.tag != null) {
                    byTag.remove(peer.tag);
                }
                peer.senderKey = E2eCrypto.decode(keyText);
                peer.tag = tagText;
                peer.adpcm = Boolean.TRUE.equals(MiniJson.path(message, "adpcm"));
                if (engine != null && engine.sendCodec() == null) {
                    chooseSendCodec(message, peer.adpcm);
                }
                peer.lastCounter = -1;
                byTag.put(tagText, peer);
                startAudio();
            }
        } catch (GeneralSecurityException | RuntimeException exception) {
            status = "could not secure the call with " + from;
        }
    }

    private synchronized void rotateSenderKey() {
        senderKey = E2eCrypto.random(16);
        senderTag = E2eCrypto.random(4);
        sendCounter = 0;
    }

    // ---- media ----

    /** Encrypts once with our sender key; the server copies it to everyone else in the call. */
    private void sendPacket(byte type, byte flags, byte[] data, int time) {
        byte[] key;
        byte[] head = new byte[8];
        synchronized (this) {
            key = senderKey;
            System.arraycopy(senderTag, 0, head, 0, 4);
            ByteBuffer.wrap(head, 4, 4).putInt((int) ++sendCounter);
        }
        byte[] plain = new byte[6 + data.length];
        plain[0] = type;
        plain[1] = flags;
        ByteBuffer.wrap(plain, 2, 4).putInt(time);
        System.arraycopy(data, 0, plain, 6, data.length);
        try {
            byte[] sealed = E2eCrypto.aesGcm(true, key, Arrays.copyOf(head, 12), head, plain);
            byte[] packet = new byte[8 + sealed.length];
            System.arraycopy(head, 0, packet, 0, 8);
            System.arraycopy(sealed, 0, packet, 8, sealed.length);
            sendMedia.accept(packet);
        } catch (GeneralSecurityException exception) {
            status = "could not encrypt audio";
        }
    }

    /**
     * One relayed packet (called on the network thread). The server stamps it with who sent it;
     * it is only used with that person's own sender key, so nobody can speak as someone else.
     */
    void onMedia(byte[] stamped) {
        try {
            receive(stamped);
        } catch (RuntimeException malformed) {
            // A broken packet is dropped; it must never stop the network reader.
        }
    }

    private void receive(byte[] stamped) {
        if (stamped.length < 2 || state == State.ENDED) {
            return;
        }
        int nameLength = stamped[0] & 0xFF;
        if (stamped.length < 1 + nameLength + 24) {
            return;
        }
        String sender = new String(stamped, 1, nameLength, StandardCharsets.UTF_8);
        byte[] packet = Arrays.copyOfRange(stamped, 1 + nameLength, stamped.length);
        Peer peer = byTag.get(E2eCrypto.encode(Arrays.copyOf(packet, 4)));
        if (peer == null || peer.senderKey == null || !peer.user.equals(sender)) {
            return;
        }
        long counter = ByteBuffer.wrap(packet, 4, 4).getInt() & 0xFFFFFFFFL;
        if (counter <= peer.lastCounter) {
            return; // replayed or old
        }
        byte[] head = Arrays.copyOf(packet, 8);
        byte[] plain;
        try {
            plain = E2eCrypto.aesGcm(false, peer.senderKey, Arrays.copyOf(head, 12), head,
                    Arrays.copyOfRange(packet, 8, packet.length));
        } catch (GeneralSecurityException tampered) {
            return;
        }
        if (plain.length < 6) {
            return;
        }
        peer.lastCounter = counter;
        int time = ByteBuffer.wrap(plain, 2, 4).getInt();
        byte[] body = Arrays.copyOfRange(plain, 6, plain.length);
        if (plain[0] == PING) {
            sendPacket(PONG, (byte) 0, E2eCrypto.decode(peer.tag), time);
        } else if (plain[0] == PONG) {
            if (Arrays.equals(body, senderTag)) {
                pingMs = (int) (System.nanoTime() / 1_000_000) - time;
                if (engine != null) {
                    engine.setRttMs(pingMs);
                }
                changed.run();
            }
        } else if (plain[0] == AUDIO && engine != null) {
            engine.receive(peer.user, counter, plain[1], body); // ordered and concealed in its jitter buffer
        } else if (plain[0] == AUDIO && (plain[1] == MULAW || plain[1] == ADPCM)) {
            short[] pcm;
            if (plain[1] == ADPCM) {
                pcm = adpcmDecode(body);
            } else {
                pcm = new short[body.length * 2]; // 8 kHz G.711 played at 16 kHz: each sample twice
                for (int index = 0; index < body.length; index++) {
                    pcm[2 * index] = pcm[2 * index + 1] = mulawDecode(body[index]);
                }
            }
            peer.voice.addLast(pcm);
            while (peer.voice.size() > MAX_QUEUED_FRAMES) {
                peer.voice.pollFirst(); // keep delay low: drop the oldest audio
            }
        }
    }

    /**
     * Calls v2: the voice codec this side sends, fixed for the call. Uses the codecs the other side
     * announced; an older build that only says "adpcm" can play ADPCM 16 kHz and G.711.
     */
    private void chooseSendCodec(Object message, boolean legacyAdpcm) {
        java.util.List<Integer> theirs = new java.util.ArrayList<>();
        if (MiniJson.path(message, "codecs") instanceof java.util.List<?> listed) {
            for (Object id : listed) {
                if (id instanceof Number number && number.intValue() >= 1 && number.intValue() <= 16) {
                    theirs.add(number.intValue());
                }
            }
        } else {
            if (legacyAdpcm) {
                theirs.add(edu.ember.client.call.AdpcmCodec.ID_16K);
            }
            theirs.add(edu.ember.client.call.MulawCodec.ID);
        }
        try {
            engine.setSendCodec(edu.ember.client.call.CodecFactory.negotiate(policy.desktopCodecPreference(), theirs));
        } catch (edu.ember.client.call.CodecNegotiationException none) {
            status = none.getMessage();
        }
    }

    /** Opens the microphone and speaker once the first person's key arrives. */
    private synchronized void startAudio() {
        if (engine != null) {
            if (state != State.ENDED && !engine.running()) {
                try {
                    engine.start();
                    state = State.ACTIVE;
                    startedMs = System.currentTimeMillis();
                    status = "relay · encrypted · " + (engine.sendCodec() == null ? "" : engine.sendCodec().name());
                } catch (edu.ember.client.call.AudioDeviceException failure) {
                    status = "no microphone or speaker found";
                }
                changed.run();
            }
            return;
        }
        if (captureThread != null || state == State.ENDED) {
            return;
        }
        try {
            microphone = AudioSystem.getTargetDataLine(VOICE);
            microphone.open(VOICE, FRAME_SAMPLES * 2 * 4);
            speaker = AudioSystem.getSourceDataLine(VOICE);
            speaker.open(VOICE, FRAME_SAMPLES * 2 * 6);
        } catch (LineUnavailableException | IllegalArgumentException | SecurityException exception) {
            status = "no microphone or speaker found";
            changed.run();
            return;
        }
        microphone.start();
        speaker.start();
        state = State.ACTIVE;
        startedMs = System.currentTimeMillis();
        status = "relay · encrypted";
        captureThread = new Thread(this::capture, "ember-call-capture");
        mixerThread = new Thread(this::mix, "ember-call-mixer");
        captureThread.setDaemon(true);
        mixerThread.setDaemon(true);
        captureThread.start();
        mixerThread.start();
    }

    private void capture() {
        byte[] frame = new byte[FRAME_SAMPLES * 2];
        long lastPing = 0;
        while (state != State.ENDED) {
            int read = microphone.read(frame, 0, frame.length);
            if (read < frame.length) {
                continue;
            }
            int now = (int) (System.nanoTime() / 1_000_000);
            if (now - lastPing > 2000) {
                lastPing = now;
                sendPacket(PING, (byte) 0, new byte[0], now);
            }
            if (muted) {
                continue;
            }
            short[] pcm = new short[FRAME_SAMPLES];
            for (int index = 0; index < FRAME_SAMPLES; index++) {
                pcm[index] = (short) ((frame[2 * index] & 0xFF) | (frame[2 * index + 1] << 8));
            }
            if (peers.values().stream().filter(peer -> peer.senderKey != null).allMatch(peer -> peer.adpcm)) {
                sendPacket(AUDIO, ADPCM, adpcmEncode(pcm, adpcmIndex), now); // wideband when everyone can play it
            } else {
                byte[] mulaw = new byte[FRAME_SAMPLES / 2];
                for (int index = 0; index < mulaw.length; index++) {
                    mulaw[index] = mulawEncode((short) ((pcm[2 * index] + pcm[2 * index + 1]) / 2));
                }
                sendPacket(AUDIO, MULAW, mulaw, now);
            }
        }
    }

    /** Adds up everyone's voice 20 ms at a time; the speaker's small buffer paces the loop. */
    private void mix() {
        byte[] out = new byte[FRAME_SAMPLES * 2];
        while (state != State.ENDED) {
            int[] sum = new int[FRAME_SAMPLES];
            for (Peer peer : peers.values()) {
                short[] frame = peer.voice.pollFirst();
                if (frame != null) {
                    for (int index = 0; index < Math.min(frame.length, FRAME_SAMPLES); index++) {
                        sum[index] += frame[index];
                    }
                }
            }
            for (int index = 0; index < FRAME_SAMPLES; index++) {
                int sample = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, sum[index]));
                out[2 * index] = (byte) sample;
                out[2 * index + 1] = (byte) (sample >> 8);
            }
            speaker.write(out, 0, out.length);
        }
    }

    /**
     * IMA ADPCM, byte for byte the same as the website's adpcmEncode: [first sample, 2 bytes LE]
     * [step index] then one 4-bit code per further sample (low nibble first). 64 kbit/s at 16 kHz.
     */
    public static byte[] adpcmEncode(short[] samples, int[] state) {
        byte[] out = new byte[3 + (samples.length) / 2];
        int predicted = samples[0];
        int index = state[0];
        out[0] = (byte) predicted;
        out[1] = (byte) (predicted >> 8);
        out[2] = (byte) index;
        for (int sample = 1; sample < samples.length; sample++) {
            int diff = samples[sample] - predicted;
            int code = 0;
            int step = IMA_STEP[index];
            int delta = step >> 3;
            if (diff < 0) {
                code = 8;
                diff = -diff;
            }
            if (diff >= step) {
                code |= 4;
                diff -= step;
                delta += step;
            }
            step >>= 1;
            if (diff >= step) {
                code |= 2;
                diff -= step;
                delta += step;
            }
            step >>= 1;
            if (diff >= step) {
                code |= 1;
                delta += step;
            }
            predicted = Math.max(-32768, Math.min(32767, (code & 8) != 0 ? predicted - delta : predicted + delta));
            index = Math.max(0, Math.min(88, index + IMA_INDEX[code]));
            int at = 3 + ((sample - 1) >> 1);
            out[at] = (byte) (out[at] | (((sample - 1) & 1) != 0 ? code << 4 : code));
        }
        state[0] = index;
        return out;
    }

    /** Frames hold an even number of samples (320), so the last half-byte is padding. */
    public static short[] adpcmDecode(byte[] bytes) {
        if (bytes.length < 4) {
            return new short[0];
        }
        short[] out = new short[(bytes.length - 3) * 2];
        int predicted = (short) ((bytes[0] & 0xFF) | (bytes[1] << 8));
        int index = Math.min(88, bytes[2] & 0xFF);
        out[0] = (short) predicted;
        for (int sample = 1; sample < out.length; sample++) {
            int packed = bytes[3 + ((sample - 1) >> 1)] & 0xFF;
            int code = (((sample - 1) & 1) != 0 ? packed >> 4 : packed) & 15;
            int step = IMA_STEP[index];
            int delta = step >> 3;
            if ((code & 4) != 0) {
                delta += step;
            }
            if ((code & 2) != 0) {
                delta += step >> 1;
            }
            if ((code & 1) != 0) {
                delta += step >> 2;
            }
            predicted = Math.max(-32768, Math.min(32767, (code & 8) != 0 ? predicted - delta : predicted + delta));
            index = Math.max(0, Math.min(88, index + IMA_INDEX[code]));
            out[sample] = (short) predicted;
        }
        return out;
    }

    /** G.711 mu-law, byte for byte the same as the website's mulawEncode. */
    static byte mulawEncode(short pcm) {
        int sample = Math.max(-32635, Math.min(32635, (int) pcm));
        int sign = sample < 0 ? 0x80 : 0;
        if (sign != 0) {
            sample = -sample;
        }
        sample += 0x84;
        int exponent = 7;
        for (int mask = 0x4000; (sample & mask) == 0 && exponent > 0; mask >>= 1) {
            exponent--;
        }
        return (byte) (~(sign | (exponent << 4) | ((sample >> (exponent + 3)) & 0x0F)) & 0xFF);
    }

    static short mulawDecode(byte value) {
        int bits = ~value & 0xFF;
        int exponent = (bits >> 4) & 7;
        int sample = ((((bits & 0x0F) << 3) + 0x84) << exponent) - 0x84;
        return (short) ((bits & 0x80) != 0 ? -sample : sample);
    }

    /** Frees the microphone, speaker and threads. */
    synchronized void end(String why) {
        if (state == State.ENDED) {
            return;
        }
        state = State.ENDED;
        status = why;
        if (engine != null) {
            engine.stop(); // its threads close the devices in finally
        }
        if (microphone != null) {
            microphone.stop();
            microphone.close();
        }
        if (speaker != null) {
            speaker.stop();
            speaker.close();
        }
        changed.run();
    }
}
