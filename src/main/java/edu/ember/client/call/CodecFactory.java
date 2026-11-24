package edu.ember.client.call;

import java.util.List;

/**
 * Picks the voice codec this side sends, once per call: the first codec in our preference order
 * that the other side said it can play. Each side chooses for its own sending direction.
 * @concept Factory pattern, static methods
 */
public final class CodecFactory {
    /** Every codec this desktop build can play, announced in the encrypted caps message. */
    public static final List<Integer> DESKTOP_DECODES = List.of(AdpcmCodec.ID_32K, AdpcmCodec.ID_16K, MulawCodec.ID);

    private CodecFactory() {
    }

    /**
     * @param preference our codecs, best first (from the quality policy)
     * @param remoteDecodes codec ids the other side announced
     * @throws CodecNegotiationException when nothing is shared
     */
    public static AudioCodec negotiate(int[] preference, List<Integer> remoteDecodes) throws CodecNegotiationException {
        for (int id : preference) {
            if (remoteDecodes.contains(id)) {
                return create(id);
            }
        }
        throw new CodecNegotiationException("No shared voice codec (they offer " + remoteDecodes + ")");
    }

    /** A decoder for a codec id that arrives in a packet, or null for an id this build cannot play. */
    public static AudioCodec forId(int id) {
        return switch (id) {
            case AdpcmCodec.ID_16K -> new AdpcmCodec(16_000);
            case AdpcmCodec.ID_32K -> new AdpcmCodec(32_000);
            case MulawCodec.ID -> new MulawCodec();
            default -> null;
        };
    }

    private static AudioCodec create(int id) throws CodecNegotiationException {
        AudioCodec codec = forId(id);
        if (codec == null) {
            throw new CodecNegotiationException("Codec " + id + " is not built in");
        }
        return codec;
    }
}
