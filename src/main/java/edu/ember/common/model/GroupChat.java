package edu.ember.common.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** A group conversation with a moderator and student members. @concept classes, encapsulation */
public final class GroupChat {
    private final String groupId;
    private final String name;
    private final String description;
    private final LinkedHashMap<String, UserRole> members;

    public GroupChat(String name, String description, String creatorUserId) {
        this(UUID.randomUUID().toString(), name, description,
                Map.of(creatorUserId, UserRole.MODERATOR));
    }

    public GroupChat(String groupId, String name, String description, Map<String, UserRole> members) {
        this.groupId = groupId;
        this.name = name;
        this.description = description;
        this.members = new LinkedHashMap<>(members);
    }

    public String groupId() { return groupId; }
    public String name() { return name; }
    public String description() { return description; }
    public synchronized Map<String, UserRole> members() { return Map.copyOf(members); }
    public synchronized boolean contains(String userId) { return members.containsKey(userId); }
    public synchronized UserRole roleOf(String userId) { return members.get(userId); }

    public synchronized void addMember(String userId) {
        members.putIfAbsent(userId, UserRole.STUDENT);
    }

    public synchronized void removeMember(String userId) {
        members.remove(userId);
    }

    public synchronized void promote(String userId) {
        if (members.containsKey(userId)) {
            members.put(userId, UserRole.MODERATOR);
        }
    }
}
