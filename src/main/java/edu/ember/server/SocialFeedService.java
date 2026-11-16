package edu.ember.server;

import edu.ember.common.exceptions.AuthorizationException;
import edu.ember.common.exceptions.MediaValidationException;
import edu.ember.common.exceptions.ResourceNotFoundException;
import edu.ember.common.exceptions.ValidationException;
import edu.ember.common.model.MediaAsset;
import edu.ember.common.model.PostComment;
import edu.ember.common.model.SocialPost;
import edu.ember.common.model.UserProfile;
import edu.ember.common.security.ImageValidator;
import edu.ember.server.storage.StorageEngine;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Applies ownership and input rules to Campus Pulse operations. @concept facade, authorization */
public final class SocialFeedService {
    private final StorageEngine storage;
    private final ImageValidator imageValidator = new ImageValidator();
    private final Path mediaDirectory;

    public SocialFeedService(StorageEngine storage, Path mediaDirectory) throws IOException {
        this.storage = storage;
        this.mediaDirectory = mediaDirectory.toAbsolutePath().normalize();
        Files.createDirectories(this.mediaDirectory);
    }

    public UserProfile updateProfile(String requester, String displayName, String bio)
            throws IOException, ValidationException, ResourceNotFoundException {
        if (displayName == null || displayName.isBlank() || displayName.length() > 40
                || bio == null || bio.length() > 280) {
            throw new ValidationException("Profile text exceeds its allowed length");
        }
        UserProfile previous = profile(requester);
        UserProfile updated = new UserProfile(requester, displayName.strip(), bio.strip(),
                previous.avatarMediaId());
        storage.saveProfile(updated);
        return updated;
    }

    public UserProfile profile(String userId) throws IOException, ResourceNotFoundException {
        return storage.findProfile(userId).orElseThrow(
                () -> new ResourceNotFoundException("Profile not found"));
    }

    public void follow(String requester, String target, boolean following)
            throws IOException, ValidationException, ResourceNotFoundException {
        if (requester.equals(target)) { throw new ValidationException("You cannot follow yourself"); }
        profile(target);
        storage.setFollow(requester, target, following);
    }

    public MediaAsset upload(String owner, byte[] bytes, String suppliedName)
            throws IOException, MediaValidationException {
        ImageValidator.ValidatedImage checked = imageValidator.validate(bytes, suppliedName);
        String id = UUID.randomUUID().toString();
        String fullName = id + ".jpg";
        String thumbName = id + "_thumb.jpg";
        Path fullPath = safePath(fullName);
        Path thumbPath = safePath(thumbName);
        Files.write(fullPath, checked.fullJpeg(), StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
        try {
            Files.write(thumbPath, checked.thumbnailJpeg(), StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
            MediaAsset asset = new MediaAsset(id, owner, fullName, thumbName,
                    checked.width(), checked.height(), System.currentTimeMillis());
            storage.saveMedia(asset);
            return asset;
        } catch (IOException exception) {
            Files.deleteIfExists(fullPath);
            Files.deleteIfExists(thumbPath);
            throw exception;
        }
    }

    public SocialPost createPost(String owner, String mediaId, String caption)
            throws IOException, ValidationException, ResourceNotFoundException,
            AuthorizationException {
        if (caption == null || caption.length() > 1000) {
            throw new ValidationException("Caption must be at most 1000 characters");
        }
        MediaAsset asset = storage.findMedia(mediaId).orElseThrow(
                () -> new ResourceNotFoundException("Media not found"));
        if (!asset.ownerUserId().equals(owner)) {
            throw new AuthorizationException("Media belongs to another account");
        }
        SocialPost post = new SocialPost(UUID.randomUUID().toString(), owner, mediaId,
                caption.strip(), System.currentTimeMillis(), 0, 0, false);
        storage.savePost(post);
        return post;
    }

    public SocialPost interact(String requester, String postId, String action, String text)
            throws IOException, ValidationException, ResourceNotFoundException,
            AuthorizationException {
        SocialPost post = storage.findPost(postId, requester).orElseThrow(
                () -> new ResourceNotFoundException("Post not found"));
        if ("like".equals(action) || "unlike".equals(action)) {
            storage.setPostLike(postId, requester, "like".equals(action));
        } else if ("comment".equals(action)) {
            if (text == null || text.isBlank() || text.length() > 500) {
                throw new ValidationException("Comment must be 1–500 characters");
            }
            storage.addComment(new PostComment(UUID.randomUUID().toString(), postId, requester,
                    text.strip(), System.currentTimeMillis()));
        } else {
            throw new ValidationException("Unsupported post interaction");
        }
        return storage.findPost(post.postId(), requester).orElseThrow(
                () -> new ResourceNotFoundException("Post not found"));
    }

    public List<SocialPost> feed(String owner, String viewer, int limit) throws IOException {
        return storage.listPosts(owner, viewer, Math.min(10, Math.max(1, limit)));
    }

    public List<PostComment> comments(String requester, String postId)
            throws IOException, ResourceNotFoundException {
        storage.findPost(postId, requester).orElseThrow(
                () -> new ResourceNotFoundException("Post not found"));
        return storage.commentsForPost(postId, 20);
    }

    public byte[] media(String requester, String mediaId, boolean thumbnail)
            throws IOException, ResourceNotFoundException {
        Optional<MediaAsset> match = storage.findMedia(mediaId);
        if (match.isEmpty() || storage.listPosts(null, requester, 500).stream()
                .noneMatch(post -> post.mediaId().equals(mediaId))) {
            throw new ResourceNotFoundException("Media not found");
        }
        String fileName = thumbnail ? match.get().thumbnailFileName() : match.get().fileName();
        return Files.readAllBytes(safePath(fileName));
    }

    private Path safePath(String name) throws IOException {
        Path resolved = mediaDirectory.resolve(name).normalize();
        if (!resolved.getParent().equals(mediaDirectory) || name.contains("/") || name.contains("\\")) {
            throw new IOException("Invalid server media name");
        }
        return resolved;
    }
}
