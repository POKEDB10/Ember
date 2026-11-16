package edu.ember.tools;

import edu.ember.common.model.GroupChat;
import edu.ember.common.model.MediaAsset;
import edu.ember.common.model.Message;
import edu.ember.common.model.PollData;
import edu.ember.common.model.PostComment;
import edu.ember.common.model.SocialPost;
import edu.ember.common.model.StoryItem;
import edu.ember.common.model.UserProfile;
import edu.ember.common.model.UserRecord;
import edu.ember.common.model.UserRole;
import edu.ember.common.security.Pbkdf2PasswordHasher;
import edu.ember.server.storage.WalStorageEngine;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Seeds three demo accounts and sample Campus Pulse/chat content. @concept DAO, factory method */
public final class DemoSeeder {
    private DemoSeeder() { throw new AssertionError("No instances"); }

    public static boolean seed(Path dataDirectory) throws Exception {
        try (WalStorageEngine storage = new WalStorageEngine(dataDirectory, true)) {
            storage.init();
            if (storage.findUser("alice").isPresent()) { return false; }
            addUsers(storage);
            addGroupContent(storage);
            addSocialContent(storage, dataDirectory.resolve("media"));
            storage.flush();
            return true;
        }
    }

    private static void addUsers(WalStorageEngine storage) throws Exception {
        Pbkdf2PasswordHasher hasher = new Pbkdf2PasswordHasher();
        List<UserRecord> users = List.of(
                seededUser(hasher, "alice", "Alice Ember", "alice-demo-42"),
                seededUser(hasher, "bob", "Bob Campus", "bob-demo-42"),
                seededUser(hasher, "charlie", "Charlie Study", "charlie-demo-42"));
        for (UserRecord user : users) {
            storage.saveUser(user);
            storage.saveProfile(new UserProfile(user.userId(), user.displayName(),
                    "Demo account · " + user.username(), ""));
        }
    }

    private static UserRecord seededUser(Pbkdf2PasswordHasher hasher, String username,
            String displayName, String password) throws Exception {
        char[] secret = password.toCharArray();
        try {
            return new UserRecord(username, username, displayName, hasher.hash(secret));
        } finally {
            java.util.Arrays.fill(secret, '\0');
        }
    }

    private static void addGroupContent(WalStorageEngine storage) throws IOException {
        GroupChat group = new GroupChat("study-group", "Campus Study Group",
                "Plan study sessions together", Map.of("alice", UserRole.MODERATOR,
                        "bob", UserRole.STUDENT, "charlie", UserRole.STUDENT));
        storage.saveGroup(group);
        storage.appendMessage(new Message("demo-welcome", "alice", "grp:study-group",
                "Welcome to Ember Study Group 🌋", System.currentTimeMillis()));
        PollData poll = new PollData("demo-poll", group.groupId(), "When should we meet?",
                List.of("Today", "Tomorrow"), List.of("alice", "bob", "charlie"));
        storage.savePoll(poll);
    }

    private static void addSocialContent(WalStorageEngine storage, Path mediaDirectory)
            throws IOException {
        Files.createDirectories(mediaDirectory);
        BufferedImage image = sampleImage();
        Path full = mediaDirectory.resolve("demo-campus.jpg");
        Path thumb = mediaDirectory.resolve("demo-campus_thumb.jpg");
        javax.imageio.ImageIO.write(image, "jpeg", full.toFile());
        BufferedImage thumbnail = new BufferedImage(32, 24, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = thumbnail.createGraphics();
        graphics.drawImage(image, 0, 0, 32, 24, null);
        graphics.dispose();
        javax.imageio.ImageIO.write(thumbnail, "jpeg", thumb.toFile());
        MediaAsset asset = new MediaAsset("demo-campus", "alice", full.getFileName().toString(),
                thumb.getFileName().toString(), image.getWidth(), image.getHeight(),
                System.currentTimeMillis());
        storage.saveMedia(asset);
        long now = System.currentTimeMillis();
        SocialPost post = new SocialPost("demo-post", "alice", asset.mediaId(),
                "Study break on campus 🌟", now, 0, 0, false);
        storage.savePost(post);
        storage.setPostLike(post.postId(), "bob", true);
        storage.addComment(new PostComment("demo-comment", post.postId(), "charlie",
                "Looks great!", now + 1));
        storage.setFollow("bob", "alice", true);
        storage.saveStory(new StoryItem("demo-story", "alice", asset.mediaId(),
                "A campus moment 🌋", now, now + 86_400_000L));
    }

    private static BufferedImage sampleImage() {
        BufferedImage image = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(92, 81, 180));
        graphics.fillRect(0, 0, 64, 48);
        graphics.setColor(new Color(245, 158, 11));
        graphics.fillOval(18, 8, 28, 28);
        graphics.dispose();
        return image;
    }
}
