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

    private static final AudioFormat VOICE = new AudioFormat(8000f, 16, 1, true, false);
    private static final int FRAME_SAMPLES = 160; // 20 ms at 8 kHz
    private static final int MAX_QUEUED_FRAMES = 8; // 160 ms per voice before old audio is dropped
    private static final byte AUDIO = 1;
    private static final byte MULAW = 2;
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

    CallSession(String me, String conversationId, String title, boolean incoming, String callId,
            BiConsumer<String, Map<String, String>> sendCall, Consumer<byte[]> sendMedia, Runnable changed) {
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
                    + MiniJson.quote(E2eCrypto.encode(senderTag)) + ",\"opus\":false,\"video\":[]}");
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
                changed.run();
            }
        } else if (plain[0] == AUDIO && plain[1] == MULAW) {
            short[] pcm = new short[body.length];
            for (int index = 0; index < body.length; index++) {
                pcm[index] = mulawDecode(body[index]);
            }
            peer.voice.addLast(pcm);
            while (peer.voice.size() > MAX_QUEUED_FRAMES) {
                peer.voice.pollFirst(); // keep delay low: drop the oldest audio
            }
        }
    }

    /** Opens the microphone and speaker once the first person's key arrives. */
    private synchronized void startAudio() {
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
            byte[] mulaw = new byte[FRAME_SAMPLES];
            for (int index = 0; index < FRAME_SAMPLES; index++) {
                mulaw[index] = mulawEncode((short) ((frame[2 * index] & 0xFF) | (frame[2 * index + 1] << 8)));
            }
            sendPacket(AUDIO, MULAW, mulaw, now);
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
