package edu.ember.server;

import edu.ember.common.exceptions.AuthorizationException;
import edu.ember.common.exceptions.MediaValidationException;
import edu.ember.common.exceptions.ResourceNotFoundException;
import edu.ember.common.exceptions.ValidationException;
import edu.ember.common.model.MediaAsset;
import edu.ember.common.model.ModerationReport;
import edu.ember.common.model.PostComment;
import edu.ember.common.model.FollowStatus;
import edu.ember.common.model.SocialPost;
import edu.ember.common.model.StoryItem;
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
    private final int storyTtlSec;

    public SocialFeedService(StorageEngine storage, Path mediaDirectory) throws IOException {
        this(storage, mediaDirectory, 86_400);
    }

    public SocialFeedService(StorageEngine storage, Path mediaDirectory, int storyTtlSec)
            throws IOException {
        if (storyTtlSec < 1) { throw new IllegalArgumentException("Story TTL must be positive"); }
        this.storage = storage;
        this.mediaDirectory = mediaDirectory.toAbsolutePath().normalize();
        this.storyTtlSec = storyTtlSec;
        Files.createDirectories(this.mediaDirectory);
        // Moving an existing server onto a replica: copy local images that are not there yet.
        try (var files = Files.list(this.mediaDirectory)) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                if (Files.isRegularFile(file) && !storage.isMediaMirrored(name)) {
                    storage.mirrorMedia(name, Files.readAllBytes(file));
                }
            }
        }
    }

    public StoryItem createStory(String owner, String mediaId, String caption)
            throws IOException, ResourceNotFoundException, ValidationException,
            AuthorizationException {
        if (caption == null || caption.length() > 280) {
            throw new ValidationException("Story caption must be at most 280 characters");
        }
        MediaAsset asset = storage.findMedia(mediaId).orElseThrow(
                () -> new ResourceNotFoundException("Media not found"));
        if (!asset.ownerUserId().equals(owner)) {
            throw new AuthorizationException("Media belongs to another account");
        }
        long created = System.currentTimeMillis();
        StoryItem story = new StoryItem(UUID.randomUUID().toString(), owner, mediaId,
                caption.strip(), created, created + storyTtlSec * 1000L);
        storage.saveStory(story);
        return story;
    }

    public List<StoryItem> stories(String viewer) throws IOException {
        long now = System.currentTimeMillis();
        List<StoryItem> visible = new java.util.ArrayList<>();
        for (StoryItem story : storage.listStories()) {
            if (story.expiresAtMs() > now && canSee(viewer, story.ownerUserId())) visible.add(story);
        }
        return List.copyOf(visible);
    }

    public UserProfile updateProfile(String requester, String displayName, String bio)
            throws IOException, ValidationException, ResourceNotFoundException {
        return updateProfile(requester, displayName, bio, profile(requester).privateAccount());
    }

    public UserProfile updateProfile(String requester, String displayName, String bio,
            boolean privateAccount)
            throws IOException, ValidationException, ResourceNotFoundException {
        if (displayName == null || displayName.isBlank() || displayName.length() > 40
                || bio == null || bio.length() > 280) {
            throw new ValidationException("Profile text exceeds its allowed length");
        }
        UserProfile previous = profile(requester);
        UserProfile updated = new UserProfile(requester, displayName.strip(), bio.strip(),
                previous.avatarMediaId(), privateAccount);
        storage.saveProfile(updated);
        return updated;
    }

    public UserProfile profile(String userId) throws IOException, ResourceNotFoundException {
        return storage.findProfile(userId).orElseThrow(
                () -> new ResourceNotFoundException("Profile not found"));
    }

    public UserProfile visibleProfile(String requester, String target)
            throws IOException, ResourceNotFoundException {
        UserProfile profile = profile(target);
        if (!canSee(requester, target)) {
            throw new ResourceNotFoundException("Profile not found");
        }
        return profile;
    }

    public void follow(String requester, String target, boolean following)
            throws IOException, ValidationException, ResourceNotFoundException {
        if (requester.equals(target)) { throw new ValidationException("You cannot follow yourself"); }
        UserProfile profile = profile(target);
        if (storage.isBlockedEitherDirection(requester, target)) {
            throw new ResourceNotFoundException("Profile not found");
        }
        if (!following) {
            storage.setFollowStatus(requester, target, null);
        } else {
            storage.setFollowStatus(requester, target,
                    profile.privateAccount() ? FollowStatus.PENDING : FollowStatus.ACCEPTED);
        }
    }

    public void approveFollow(String owner, String follower, boolean approve)
            throws IOException, ValidationException {
        if (storage.followStatus(follower, owner).orElse(null) != FollowStatus.PENDING) {
            throw new ValidationException("No pending follow request");
        }
        storage.setFollowStatus(follower, owner, approve ? FollowStatus.ACCEPTED : null);
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
            storage.mirrorMedia(fullName, checked.fullJpeg());
            storage.mirrorMedia(thumbName, checked.thumbnailJpeg());
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
        if (!canSee(requester, post.ownerUserId())) {
            throw new ResourceNotFoundException("Post not found");
        }
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

    public List<SocialPost> feed(String owner, String viewer, int limit)
            throws IOException, ResourceNotFoundException {
        if (owner != null && !owner.isBlank() && !canSee(viewer, owner)) {
            throw new ResourceNotFoundException("Profile not found");
        }
        List<SocialPost> visible = new java.util.ArrayList<>();
        for (SocialPost post : storage.listPosts(owner, viewer, Math.min(10, Math.max(1, limit)))) {
            if (canSee(viewer, post.ownerUserId())) { visible.add(post); }
        }
        return List.copyOf(visible);
    }

    public List<PostComment> comments(String requester, String postId)
            throws IOException, ResourceNotFoundException {
        var post = storage.findPost(postId, requester).orElseThrow(
                () -> new ResourceNotFoundException("Post not found"));
        if (!canSee(requester, post.ownerUserId())) {
            throw new ResourceNotFoundException("Post not found");
        }
        return storage.commentsForPost(postId, 20);
    }

    public byte[] media(String requester, String mediaId, boolean thumbnail)
            throws IOException, ResourceNotFoundException {
        Optional<MediaAsset> match = storage.findMedia(mediaId);
        boolean visible = false;
        if (match.isPresent()) {
            for (SocialPost post : storage.listPosts(null, requester, 500)) {
                if (post.mediaId().equals(mediaId) && canSee(requester, post.ownerUserId())) {
                    visible = true;
                    break;
                }
            }
        }
        if (!visible) {
            for (StoryItem story : storage.listStories()) {
                if (story.mediaId().equals(mediaId) && story.expiresAtMs() > System.currentTimeMillis()
                        && canSee(requester, story.ownerUserId())) {
                    visible = true;
                    break;
                }
            }
        }
        if (!visible) {
            throw new ResourceNotFoundException("Media not found");
        }
        String fileName = thumbnail ? match.get().thumbnailFileName() : match.get().fileName();
        Path local = safePath(fileName);
        if (!Files.exists(local)) {
            // Fresh server disk: fetch from the replica once, then serve from the local cache.
            byte[] remote = storage.fetchMirroredMedia(fileName);
            if (remote == null) {
                throw new ResourceNotFoundException("Media not found");
            }
            Files.write(local, remote);
            return remote;
        }
        return Files.readAllBytes(local);
    }

    public boolean isBlocked(String firstUserId, String secondUserId) throws IOException {
        return storage.isBlockedEitherDirection(firstUserId, secondUserId);
    }

    public ModerationReport report(String reporter, String targetType, String targetId,
            String reason) throws IOException, ValidationException, ResourceNotFoundException {
        String kind = targetType == null ? "" : targetType.toLowerCase(java.util.Locale.ROOT);
        if (!List.of("profile", "post", "message").contains(kind)
                || targetId == null || targetId.isBlank() || targetId.length() > 100
                || reason == null || reason.isBlank() || reason.length() > 300) {
            throw new ValidationException("Report type, target, or reason is invalid");
        }
        boolean exists = switch (kind) {
            case "profile" -> storage.findProfile(targetId).isPresent();
            case "post" -> storage.findPost(targetId, reporter).isPresent();
            case "message" -> storage.findByClientMsgId(targetId).isPresent();
            default -> false;
        };
        if (!exists) { throw new ResourceNotFoundException("Report target not found"); }
        ModerationReport report = new ModerationReport(UUID.randomUUID().toString(), reporter,
                kind, targetId, reason.strip(), System.currentTimeMillis());
        storage.saveReport(report);
        return report;
    }

    public void block(String requester, String target, boolean blocked)
            throws IOException, ResourceNotFoundException, ValidationException {
        if (requester.equals(target)) { throw new ValidationException("You cannot block yourself"); }
        profile(target);
        storage.setBlocked(requester, target, blocked);
    }

    private boolean canSee(String viewer, String owner) throws IOException {
        if (viewer.equals(owner)) { return true; }
        if (storage.isBlockedEitherDirection(viewer, owner)) { return false; }
        UserProfile ownerProfile = storage.findProfile(owner).orElse(null);
        return ownerProfile != null && (!ownerProfile.privateAccount()
                || storage.followStatus(viewer, owner).orElse(null) == FollowStatus.ACCEPTED);
    }

    private Path safePath(String name) throws IOException {
        Path resolved = mediaDirectory.resolve(name).normalize();
        if (!resolved.getParent().equals(mediaDirectory) || name.contains("/") || name.contains("\\")) {
            throw new IOException("Invalid server media name");
        }
        return resolved;
    }
}
