package edu.ember.server;

import edu.ember.common.exceptions.AuthorizationException;
import edu.ember.common.exceptions.ValidationException;
import edu.ember.common.model.GroupChat;
import edu.ember.common.model.UserRole;
import edu.ember.server.storage.StorageEngine;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Applies group membership and moderator rules before storage changes. @concept service layer, RBAC */
public final class GroupService {
    private final StorageEngine storage;

    public GroupService(StorageEngine storage) {
        this.storage = storage;
    }

    public GroupChat create(String creatorUserId, String name, String description)
            throws IOException, ValidationException {
        if (name == null || name.isBlank() || name.length() > 60
                || description == null || description.length() > 200) {
            throw new ValidationException("Invalid group name or description");
        }
        GroupChat group = new GroupChat(name, description, creatorUserId);
        storage.saveGroup(group);
        return group;
    }

    public void changeMember(String requester, String groupId, String target, String action)
            throws IOException, AuthorizationException, ValidationException {
        GroupChat group = storage.findGroup(groupId)
                .orElseThrow(() -> new AuthorizationException("Group not found"));
        if (group.roleOf(requester) != UserRole.MODERATOR) {
            throw new AuthorizationException("Group moderator required");
        }
        if (action.equals("ADD")) {
            if (storage.findUser(target).isEmpty()) {
                throw new ValidationException("Target user does not exist");
            }
            group.addMember(target);
        } else if (action.equals("REMOVE")) {
            group.removeMember(target);
        } else if (action.equals("PROMOTE")) {
            group.promote(target);
        } else {
            throw new ValidationException("Unknown group member action");
        }
        storage.saveGroup(group);
    }

    public GroupChat requireMember(String userId, String groupId)
            throws IOException, AuthorizationException {
        GroupChat group = storage.findGroup(groupId)
                .orElseThrow(() -> new AuthorizationException("Group not found"));
        if (!group.contains(userId)) {
            throw new AuthorizationException("Group membership required");
        }
        return group;
    }

    public List<String> memberIds(String groupId) throws IOException, AuthorizationException {
        GroupChat group = storage.findGroup(groupId)
                .orElseThrow(() -> new AuthorizationException("Group not found"));
        return List.copyOf(group.members().keySet());
    }

    public Map<String, UserRole> roles(String groupId) throws IOException, AuthorizationException {
        GroupChat group = storage.findGroup(groupId)
                .orElseThrow(() -> new AuthorizationException("Group not found"));
        return group.members();
    }
}
