package edu.ember.common.model;

/** Stores server-generated media paths and validated image dimensions. @concept records, data hiding */
public record MediaAsset(String mediaId, String ownerUserId, String fileName,
        String thumbnailFileName, int width, int height, long createdAtMs) {
}
